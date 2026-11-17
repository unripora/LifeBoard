// ---------------------------------------------------------------------------
// 桌面（Windows x86_64）版 QuickJS 桥接层
//
// 与 Android 的 jni_bridge.cpp 一一对应，但走**纯 C ABI**：
// Kotlin 侧用 JNA 按符号名直接绑定，不需要 JNI 头、不需要 JVM 生成 glue code。
//
// 为什么另写一份而不是抽公共层：
//   - jni_bridge 依赖 <jni.h>/<android/log.h> 与 jobject 全局引用语义；
//   - 桌面既没有 JVM 头文件也没有 __android_log_print。
// 抽公共层会引入 jni.h 条件编译，收益不抵复杂度（KISS）。
// 两份桥接的**对外行为约定完全一致**（返回值标记、Promise 语义、模块重置语义），
// 差异只落在「宿主函数如何回调 Kotlin」这一处。
//
// 返回值约定（与 Android 侧相同，Kotlin 层共用一套解析逻辑）：
//   - 普通 JSON 文本：成功
//   - "<error:...>"：失败
//   - "<pending>"：还在等宿主异步回调
// ---------------------------------------------------------------------------
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <string>
#include <utility>
#include <vector>
#include <unordered_map>

#include "quickjs.h"

#ifdef _WIN32
#define LB_EXPORT extern "C" __declspec(dllexport)
#else
#define LB_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {

using ModuleTable = std::unordered_map<std::string, std::string>;

/**
 * 宿主函数回调：由 Kotlin(JNA) 侧提供的函数指针。
 *
 * @param userData   不透明指针，JNA 侧传进 dispatcher 的接口对象
 * @param name       JS 侧的点分路径名，如 "ctx.http.get"
 * @param argsJson   参数数组的 JSON 文本
 * @return 结果文本（JSON / "<error:...>" / "<pending:token>"）。
 *         内存由桥接层负责释放，回调方只需保证 strdup 出来的字符串，见 lb_free_string。
 */
typedef char *(*LbHostCallback)(void *userData, const char *name, const char *argsJson);

struct HostFunctionSlot {
    std::string name;
};

struct RuntimeHandle {
    JSRuntime *rt = nullptr;
    JSContext *ctx = nullptr;
    ModuleTable modules;

    // 宿主函数回调表：所有宿主函数共享同一个回调与 userData
    LbHostCallback hostCallback = nullptr;
    void *hostUserData = nullptr;

    std::vector<HostFunctionSlot> hostFunctions;

    // token → (resolve, reject)
    std::unordered_map<std::string, std::pair<JSValue, JSValue>> pendingResolvers;

    // 模块名 → JSValue(JS_TAG_MODULE)，必须是已执行的实例
    std::unordered_map<std::string, JSValue> executedModules;

    // 调用键 → 挂起中的 Promise
    std::unordered_map<std::string, JSValue> inflightCalls;

    std::atomic<bool> interruptRequested{false};
};

RuntimeHandle *fromHandle(int64_t handle) {
    return reinterpret_cast<RuntimeHandle *>(static_cast<intptr_t>(handle));
}

std::string valueToDebugString(JSContext *ctx, JSValueConst value) {
    const char *str = JS_ToCString(ctx, value);
    if (!str) {
        return "<unstringifiable>";
    }
    std::string out(str);
    JS_FreeCString(ctx, str);
    return out;
}

std::string takeExceptionText(JSContext *ctx) {
    JSValue exception = JS_GetException(ctx);
    std::string text = valueToDebugString(ctx, exception);
    JS_FreeValue(ctx, exception);
    return text;
}

bool stringifyToJson(JSContext *ctx, JSValueConst value, std::string &out) {
    JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) {
        return false;
    }
    if (JS_IsUndefined(json)) {
        out = "null";
        JS_FreeValue(ctx, json);
        return true;
    }
    out = valueToDebugString(ctx, json);
    JS_FreeValue(ctx, json);
    return true;
}

int drainJobs(JSRuntime *rt) {
    int executed = 0;
    JSContext *jobCtx = nullptr;
    for (;;) {
        int rc = JS_ExecutePendingJob(rt, &jobCtx);
        if (rc < 0) {
            return -1;
        }
        if (rc == 0) {
            break;
        }
        executed += rc;
    }
    return executed;
}

JSValue awaitPromise(JSRuntime *rt, JSContext *ctx, JSValueConst promise, bool &settled) {
    const int kMaxDrainRounds = 10000;
    for (int i = 0; i < kMaxDrainRounds; ++i) {
        JSPromiseStateEnum state = JS_PromiseState(ctx, promise);
        if (state == JS_PROMISE_FULFILLED) {
            settled = true;
            return JS_PromiseResult(ctx, promise);
        }
        if (state == JS_PROMISE_REJECTED) {
            settled = true;
            JSValue reason = JS_PromiseResult(ctx, promise);
            JS_Throw(ctx, reason);
            return JS_EXCEPTION;
        }
        if (!JS_IsJobPending(rt)) {
            settled = false;
            return JS_UNDEFINED;
        }
        JSContext *jobCtx = nullptr;
        if (JS_ExecutePendingJob(rt, &jobCtx) < 0) {
            settled = true;
            return JS_EXCEPTION;
        }
    }
    settled = false;
    JS_ThrowInternalError(ctx, "promise did not settle within %d drain rounds",
                          kMaxDrainRounds);
    return JS_EXCEPTION;
}

int interruptHandler(JSRuntime * /*rt*/, void *opaque) {
    auto *h = static_cast<RuntimeHandle *>(opaque);
    return (h != nullptr && h->interruptRequested.load(std::memory_order_relaxed)) ? 1 : 0;
}

int setImportMeta(JSContext *ctx, JSValueConst funcVal, const std::string &url) {
    if (JS_VALUE_GET_TAG(funcVal) != JS_TAG_MODULE) {
        return -1;
    }
    auto *m = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(funcVal));
    JSValue metaObj = JS_GetImportMeta(ctx, m);
    if (JS_IsException(metaObj)) {
        return -1;
    }
    JS_SetPropertyStr(ctx, metaObj, "url", JS_NewString(ctx, url.c_str()));
    JS_FreeValue(ctx, metaObj);
    return 0;
}

JSModuleDef *moduleLoader(JSContext *ctx, const char *moduleName, void *opaque) {
    auto *h = static_cast<RuntimeHandle *>(opaque);
    if (!h) {
        JS_ThrowReferenceError(ctx, "module loader has no runtime");
        return nullptr;
    }
    auto it = h->modules.find(moduleName);
    if (it == h->modules.end()) {
        JS_ThrowReferenceError(ctx, "module not found: '%s'", moduleName);
        return nullptr;
    }
    const std::string &source = it->second;
    JSValue funcVal = JS_Eval(ctx, source.c_str(), source.size(), moduleName,
                              JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(funcVal)) {
        return nullptr;
    }
    if (setImportMeta(ctx, funcVal, std::string("lifeboard://") + moduleName) < 0) {
        JS_FreeValue(ctx, funcVal);
        return nullptr;
    }
    auto *m = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(funcVal));
    JS_FreeValue(ctx, funcVal);
    return m;
}

JSValue hostFunctionThunk(JSContext *ctx, JSValueConst /*thisVal*/,
                          int argc, JSValueConst *argv, int magic);

void defineGlobalFunction(JSContext *ctx, const std::string &globalName, int magic) {
    JSValue root = JS_GetGlobalObject(ctx);
    JSValue current = JS_DupValue(ctx, root);

    std::vector<std::string> parts;
    size_t start = 0;
    while (true) {
        size_t dot = globalName.find('.', start);
        if (dot == std::string::npos) {
            parts.push_back(globalName.substr(start));
            break;
        }
        parts.push_back(globalName.substr(start, dot - start));
        start = dot + 1;
    }

    for (size_t i = 0; i + 1 < parts.size(); ++i) {
        JSValue child = JS_GetPropertyStr(ctx, current, parts[i].c_str());
        if (JS_IsUndefined(child) || JS_IsException(child)) {
            JS_FreeValue(ctx, child);
            child = JS_NewObject(ctx);
            // JS_SetPropertyStr 接管 val 的所有权，而下面还要继续用 child
            // 作为下一层的 current，所以必须让出一份引用给属性表
            JS_SetPropertyStr(ctx, current, parts[i].c_str(), JS_DupValue(ctx, child));
        }
        JS_FreeValue(ctx, current);
        current = child;
    }

    JSValue fn = JS_NewCFunctionMagic(ctx, hostFunctionThunk, parts.back().c_str(),
                                      0, JS_CFUNC_generic_magic, magic);
    JS_SetPropertyStr(ctx, current, parts.back().c_str(), fn);

    JS_FreeValue(ctx, current);
    JS_FreeValue(ctx, root);
}

/**
 * 宿主函数桩：把调用转给 Kotlin 侧回调。
 *
 * 行为与 Android 侧完全一致：<error:...> 抛异常、<pending:token> 返回挂起 Promise、
 * 其余按 JSON 解析（解析失败退回字符串）。
 */
JSValue hostFunctionThunk(JSContext *ctx, JSValueConst /*thisVal*/,
                          int argc, JSValueConst *argv, int magic) {
    auto *h = static_cast<RuntimeHandle *>(JS_GetContextOpaque(ctx));
    if (!h) {
        return JS_ThrowInternalError(ctx, "runtime context lost");
    }
    if (magic < 0 || static_cast<size_t>(magic) >= h->hostFunctions.size()) {
        return JS_ThrowInternalError(ctx, "invalid host function slot %d", magic);
    }
    HostFunctionSlot &slot = h->hostFunctions[magic];
    if (!h->hostCallback) {
        return JS_ThrowInternalError(ctx, "host function '%s' not bound", slot.name.c_str());
    }

    JSValue arr = JS_NewArray(ctx);
    for (int i = 0; i < argc; ++i) {
        JS_SetPropertyUint32(ctx, arr, static_cast<uint32_t>(i), JS_DupValue(ctx, argv[i]));
    }
    std::string argsJson;
    if (!stringifyToJson(ctx, arr, argsJson)) {
        JS_FreeValue(ctx, arr);
        return JS_ThrowInternalError(ctx, "failed to serialize host call args");
    }
    JS_FreeValue(ctx, arr);

    char *raw = h->hostCallback(h->hostUserData, slot.name.c_str(), argsJson.c_str());
    if (!raw) {
        return JS_ThrowInternalError(ctx, "host function '%s' returned null", slot.name.c_str());
    }
    std::string resultText(raw);
    // 刻意不 free(raw)：
    //   桌面侧的返回值由 Kotlin(JNA) 用 `com.sun.jna.Memory` 分配，那是 JVM 托管内存，
    //   交给 C 的 free 会破坏堆 —— Windows 上进程直接以 0xC0000374 退出，
    //   而且没有任何 Java 异常栈可看，极难定位。
    //   Kotlin 侧自己持有这些缓冲直到确认 native 读完（见 returnedBuffers）。
    //
    //   注意：Android 侧走的是 jni_bridge.cpp，那边仍是 strdup 分配 + 本方 free，
    //   两份桥接在「谁负责释放」上刻意不同，不能照抄。

    if (resultText.rfind("<error:", 0) == 0) {
        std::string msg = resultText.substr(7);
        if (!msg.empty() && msg.back() == '>') msg.pop_back();
        return JS_ThrowInternalError(ctx, "%s", msg.c_str());
    }

    if (resultText.rfind("<pending:", 0) == 0) {
        std::string token = resultText.substr(9);
        if (!token.empty() && token.back() == '>') token.pop_back();

        JSValue resolving[2];
        JSValue promise = JS_NewPromiseCapability(ctx, resolving);

        auto existing = h->pendingResolvers.find(token);
        if (existing != h->pendingResolvers.end()) {
            JS_FreeValue(ctx, existing->second.first);
            JS_FreeValue(ctx, existing->second.second);
        }
        h->pendingResolvers[token] = std::make_pair(resolving[0], resolving[1]);
        return promise;
    }

    if (resultText == "<pending>") {
        return JS_ThrowInternalError(
                ctx, "host function '%s' returned <pending> without token", slot.name.c_str());
    }

    JSValue parsed = JS_ParseJSON(ctx, resultText.c_str(), resultText.size(), "<host-result>");
    if (JS_IsException(parsed)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        return JS_NewString(ctx, resultText.c_str());
    }
    return parsed;
}

void reinstallHostFunctions(RuntimeHandle *h) {
    if (!h->ctx) {
        return;
    }
    JS_SetContextOpaque(h->ctx, h);
    for (size_t i = 0; i < h->hostFunctions.size(); ++i) {
        defineGlobalFunction(h->ctx, h->hostFunctions[i].name, static_cast<int>(i));
    }
}

/** 复制一个 C 字符串到堆上，交给 Kotlin(JNA) 侧，由 lb_free_string 释放。 */
char *dupOut(const std::string &text) {
    auto *buf = static_cast<char *>(malloc(text.size() + 1));
    if (!buf) {
        return nullptr;
    }
    memcpy(buf, text.c_str(), text.size() + 1);
    return buf;
}

}  // namespace

// ---------------------------------------------------------------------------
// 对外 C ABI
// ---------------------------------------------------------------------------

LB_EXPORT void lb_free_string(char *text) {
    free(text);
}

LB_EXPORT int64_t lb_create(void) {
    auto *handle = new RuntimeHandle();

    handle->rt = JS_NewRuntime();
    if (!handle->rt) {
        delete handle;
        return 0;
    }
    JS_SetMemoryLimit(handle->rt, 64 * 1024 * 1024);
    JS_SetMaxStackSize(handle->rt, 1024 * 1024);
    JS_SetModuleLoaderFunc(handle->rt, nullptr, moduleLoader, handle);
    JS_SetInterruptHandler(handle->rt, interruptHandler, handle);

    handle->ctx = JS_NewContext(handle->rt);
    if (!handle->ctx) {
        JS_FreeRuntime(handle->rt);
        delete handle;
        return 0;
    }
    JS_SetContextOpaque(handle->ctx, handle);

    return static_cast<int64_t>(reinterpret_cast<intptr_t>(handle));
}

LB_EXPORT void lb_destroy(int64_t handle) {
    auto *h = fromHandle(handle);
    if (!h) {
        return;
    }
    for (auto &kv : h->pendingResolvers) {
        JS_FreeValue(h->ctx, kv.second.first);
        JS_FreeValue(h->ctx, kv.second.second);
    }
    h->pendingResolvers.clear();
    for (auto &kv : h->executedModules) {
        JS_FreeValue(h->ctx, kv.second);
    }
    h->executedModules.clear();
    for (auto &kv : h->inflightCalls) {
        JS_FreeValue(h->ctx, kv.second);
    }
    h->inflightCalls.clear();

    if (h->ctx) {
        JS_FreeContext(h->ctx);
    }
    if (h->rt) {
        JS_FreeRuntime(h->rt);
    }
    delete h;
}

LB_EXPORT char *lb_evaluate(int64_t handle, const char *code, const char *filename) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return dupOut("<error:no-runtime>");
    }
    JSValue result = JS_Eval(h->ctx, code, strlen(code), filename, JS_EVAL_TYPE_GLOBAL);

    std::string out;
    if (JS_IsException(result)) {
        out = "<error:" + takeExceptionText(h->ctx) + ">";
    } else {
        out = valueToDebugString(h->ctx, result);
    }
    JS_FreeValue(h->ctx, result);
    drainJobs(h->rt);
    return dupOut(out);
}

/**
 * 注册模块源码表。
 *
 * @param namesJson   模块名的 JSON 字符串数组
 * @param sourcesJson 与 namesJson 等长、等序的源码字符串数组
 *
 * 之所以用 JSON 数组而不是 C 数组指针：JNA 传字符串数组需要额外的
 * Pointer 数组构造，而 JSON 只需一次 marshal；模块数量在几十个量级，
 * 一次 JSON 解析的开销可以忽略，换来的是绑定代码更简单。
 */
LB_EXPORT void lb_set_modules(int64_t handle, const char *namesJson, const char *sourcesJson) {
    auto *h = fromHandle(handle);
    if (!h || !h->rt || !h->ctx) {
        return;
    }

    h->modules.clear();

    // 用一个临时 JSContext 解析 JSON 数组，避免污染主 ctx
    JSRuntime *tmpRt = JS_NewRuntime();
    if (!tmpRt) {
        return;
    }
    JSContext *tmpCtx = JS_NewContext(tmpRt);
    if (!tmpCtx) {
        JS_FreeRuntime(tmpRt);
        return;
    }

    auto readStringArray = [&](const char *json) -> std::vector<std::string> {
        std::vector<std::string> out;
        JSValue parsed = JS_ParseJSON(tmpCtx, json, strlen(json), "<modules>");
        if (JS_IsException(parsed)) {
            JS_FreeValue(tmpCtx, JS_GetException(tmpCtx));
            return out;
        }
        if (JS_IsArray(parsed)) {
            JSValue lenVal = JS_GetPropertyStr(tmpCtx, parsed, "length");
            int32_t len = 0;
            JS_ToInt32(tmpCtx, &len, lenVal);
            JS_FreeValue(tmpCtx, lenVal);
            for (int32_t i = 0; i < len; ++i) {
                JSValue item = JS_GetPropertyUint32(tmpCtx, parsed, static_cast<uint32_t>(i));
                out.push_back(valueToDebugString(tmpCtx, item));
                JS_FreeValue(tmpCtx, item);
            }
        }
        JS_FreeValue(tmpCtx, parsed);
        return out;
    };

    std::vector<std::string> names = readStringArray(namesJson);
    std::vector<std::string> sources = readStringArray(sourcesJson);

    JS_FreeContext(tmpCtx);
    JS_FreeRuntime(tmpRt);

    if (names.size() != sources.size()) {
        return;
    }
    for (size_t i = 0; i < names.size(); ++i) {
        h->modules[names[i]] = sources[i];
    }

    // 丢弃旧 ctx（模块缓存是 ctx 级的），旧 ctx 上的所有 JSValue 必须先释放
    for (auto &kv : h->executedModules) {
        JS_FreeValue(h->ctx, kv.second);
    }
    h->executedModules.clear();
    for (auto &kv : h->inflightCalls) {
        JS_FreeValue(h->ctx, kv.second);
    }
    h->inflightCalls.clear();
    for (auto &kv : h->pendingResolvers) {
        JS_FreeValue(h->ctx, kv.second.first);
        JS_FreeValue(h->ctx, kv.second.second);
    }
    h->pendingResolvers.clear();

    JS_FreeContext(h->ctx);
    h->ctx = JS_NewContext(h->rt);
    if (h->ctx) {
        reinstallHostFunctions(h);
    }
}

LB_EXPORT void lb_register_host_functions(int64_t handle, const char *namesJson,
                                          LbHostCallback callback, void *userData) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx || !callback) {
        return;
    }
    h->hostCallback = callback;
    h->hostUserData = userData;
    h->hostFunctions.clear();

    JSRuntime *tmpRt = JS_NewRuntime();
    if (!tmpRt) {
        return;
    }
    JSContext *tmpCtx = JS_NewContext(tmpRt);
    if (!tmpCtx) {
        JS_FreeRuntime(tmpRt);
        return;
    }
    JSValue parsed = JS_ParseJSON(tmpCtx, namesJson, strlen(namesJson), "<host-names>");
    if (!JS_IsException(parsed) && JS_IsArray(parsed)) {
        JSValue lenVal = JS_GetPropertyStr(tmpCtx, parsed, "length");
        int32_t len = 0;
        JS_ToInt32(tmpCtx, &len, lenVal);
        JS_FreeValue(tmpCtx, lenVal);
        for (int32_t i = 0; i < len; ++i) {
            JSValue item = JS_GetPropertyUint32(tmpCtx, parsed, static_cast<uint32_t>(i));
            HostFunctionSlot slot;
            slot.name = valueToDebugString(tmpCtx, item);
            JS_FreeValue(tmpCtx, item);
            h->hostFunctions.push_back(slot);
            defineGlobalFunction(h->ctx, slot.name, static_cast<int>(h->hostFunctions.size()) - 1);
        }
    } else if (JS_IsException(parsed)) {
        JS_FreeValue(tmpCtx, JS_GetException(tmpCtx));
    }
    JS_FreeValue(tmpCtx, parsed);
    JS_FreeContext(tmpCtx);
    JS_FreeRuntime(tmpRt);
}

JSValue getModuleNamespace(RuntimeHandle *h, const std::string &name) {
    auto execIt = h->executedModules.find(name);
    if (execIt == h->executedModules.end()) {
        JS_ThrowReferenceError(h->ctx, "module not executed yet: '%s'", name.c_str());
        return JS_EXCEPTION;
    }
    auto *moduleDef = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(execIt->second));
    return JS_GetModuleNamespace(h->ctx, moduleDef);
}

LB_EXPORT char *lb_evaluate_module(int64_t handle, const char *moduleName) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return dupOut("<error:no-runtime>");
    }
    std::string name(moduleName);

    auto it = h->modules.find(name);
    if (it == h->modules.end()) {
        return dupOut("<error:module not registered: '" + name + "'>");
    }
    const std::string &source = it->second;

    JSValue funcVal = JS_Eval(h->ctx, source.c_str(), source.size(), name.c_str(),
                              JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(funcVal)) {
        return dupOut("<error:" + takeExceptionText(h->ctx) + ">");
    }
    if (setImportMeta(h->ctx, funcVal, "lifeboard://" + name) < 0) {
        JS_FreeValue(h->ctx, funcVal);
        return dupOut("<error:import.meta injection failed>");
    }

    JSValue moduleRef = JS_DupValue(h->ctx, funcVal);
    JSValue result = JS_EvalFunction(h->ctx, funcVal);
    if (JS_IsException(result)) {
        std::string out = "<error:" + takeExceptionText(h->ctx) + ">";
        JS_FreeValue(h->ctx, moduleRef);
        return dupOut(out);
    }
    JS_FreeValue(h->ctx, result);
    drainJobs(h->rt);

    auto prev = h->executedModules.find(name);
    if (prev != h->executedModules.end()) {
        JS_FreeValue(h->ctx, prev->second);
    }
    h->executedModules[name] = moduleRef;

    std::string out = "[]";
    JSValue ns = JS_GetModuleNamespace(
            h->ctx, static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(moduleRef)));
    if (!JS_IsException(ns)) {
        JSPropertyEnum *tab = nullptr;
        uint32_t len = 0;
        if (JS_GetOwnPropertyNames(h->ctx, &tab, &len, ns,
                                   JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) == 0) {
            JSValue arr = JS_NewArray(h->ctx);
            for (uint32_t i = 0; i < len; ++i) {
                const char *key = JS_AtomToCString(h->ctx, tab[i].atom);
                if (key) {
                    JS_SetPropertyUint32(h->ctx, arr, i, JS_NewString(h->ctx, key));
                    JS_FreeCString(h->ctx, key);
                }
            }
            JS_FreePropertyEnum(h->ctx, tab, len);
            JSValue json = JS_JSONStringify(h->ctx, arr, JS_UNDEFINED, JS_UNDEFINED);
            if (!JS_IsException(json)) {
                out = valueToDebugString(h->ctx, json);
                JS_FreeValue(h->ctx, json);
            }
            JS_FreeValue(h->ctx, arr);
        }
        JS_FreeValue(h->ctx, ns);
    }
    return dupOut(out);
}

LB_EXPORT int lb_resolve_promise(int64_t handle, const char *token,
                                 const char *resultJson, const char *errorText) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return 0;
    }
    std::string tokenStr(token);
    auto it = h->pendingResolvers.find(tokenStr);
    if (it == h->pendingResolvers.end()) {
        return 0;
    }

    JSValue resolveFn = it->second.first;
    JSValue rejectFn = it->second.second;
    h->pendingResolvers.erase(it);

    bool isError = errorText != nullptr;
    JSValue arg;
    if (isError) {
        // 必须造 Error 对象，理由同 Android 侧：裸字符串会让 e.message 是 undefined
        arg = JS_NewError(h->ctx);
        if (!JS_IsException(arg)) {
            JS_SetPropertyStr(h->ctx, arg, "message", JS_NewString(h->ctx, errorText));
        } else {
            JS_FreeValue(h->ctx, JS_GetException(h->ctx));
            arg = JS_NewString(h->ctx, errorText);
        }
    } else {
        std::string resText(resultJson ? resultJson : "");
        if (resText.empty()) {
            arg = JS_UNDEFINED;
        } else {
            arg = JS_ParseJSON(h->ctx, resText.c_str(), resText.size(), "<resolve>");
            if (JS_IsException(arg)) {
                JS_FreeValue(h->ctx, JS_GetException(h->ctx));
                arg = JS_NewString(h->ctx, resText.c_str());
            }
        }
    }

    JSValue callResult = JS_Call(h->ctx, isError ? rejectFn : resolveFn, JS_UNDEFINED, 1, &arg);
    JS_FreeValue(h->ctx, arg);
    JS_FreeValue(h->ctx, resolveFn);
    JS_FreeValue(h->ctx, rejectFn);

    bool ok = !JS_IsException(callResult);
    if (!ok) {
        takeExceptionText(h->ctx);
    }
    JS_FreeValue(h->ctx, callResult);
    // 刻意不 drainJobs：避免在宿主函数执行期间重入 JS 栈
    return ok ? 1 : 0;
}

LB_EXPORT int lb_drain_jobs(int64_t handle) {
    auto *h = fromHandle(handle);
    if (!h || !h->rt) {
        return -1;
    }
    return drainJobs(h->rt);
}

LB_EXPORT int lb_drop_call(int64_t handle, const char *callKey) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx || !callKey) {
        return 0;
    }
    auto it = h->inflightCalls.find(std::string(callKey));
    if (it == h->inflightCalls.end()) {
        return 0;
    }
    JS_FreeValue(h->ctx, it->second);
    h->inflightCalls.erase(it);
    return 1;
}

LB_EXPORT void lb_request_interrupt(int64_t handle) {
    auto *h = fromHandle(handle);
    if (h) {
        h->interruptRequested.store(true, std::memory_order_relaxed);
    }
}

LB_EXPORT void lb_clear_interrupt(int64_t handle) {
    auto *h = fromHandle(handle);
    if (h) {
        h->interruptRequested.store(false, std::memory_order_relaxed);
    }
}

LB_EXPORT void lb_gc(int64_t handle) {
    auto *h = fromHandle(handle);
    if (h && h->rt) {
        JS_RunGC(h->rt);
    }
}

LB_EXPORT char *lb_call_function(int64_t handle, const char *moduleName,
                                 const char *functionName, const char *argsJson,
                                 const char *callKey) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return dupOut("<error:no-runtime>");
    }

    std::string modName(moduleName);
    std::string fnName(functionName);
    std::string argsText(argsJson ? argsJson : "[]");
    std::string keyStr(callKey ? callKey : "");

    h->interruptRequested.store(false, std::memory_order_relaxed);

    JSValue pendingPromise = JS_UNDEFINED;
    if (!keyStr.empty()) {
        auto inflight = h->inflightCalls.find(keyStr);
        if (inflight != h->inflightCalls.end()) {
            pendingPromise = JS_DupValue(h->ctx, inflight->second);
        }
    }

    if (JS_IsUndefined(pendingPromise)) {
        JSValue ns = getModuleNamespace(h, modName);
        if (JS_IsException(ns)) {
            return dupOut("<error:" + takeExceptionText(h->ctx) + ">");
        }
        JSValue fn = JS_GetPropertyStr(h->ctx, ns, fnName.c_str());
        JS_FreeValue(h->ctx, ns);

        if (JS_IsException(fn)) {
            return dupOut("<error:" + takeExceptionText(h->ctx) + ">");
        }
        if (!JS_IsFunction(h->ctx, fn)) {
            JS_FreeValue(h->ctx, fn);
            return dupOut("<error:not a function: '" + fnName + "'>");
        }

        std::vector<JSValue> argv;
        if (!argsText.empty() && argsText != "null" && argsText != "[]") {
            JSValue parsed = JS_ParseJSON(h->ctx, argsText.c_str(), argsText.size(), "<args>");
            if (JS_IsException(parsed)) {
                JS_FreeValue(h->ctx, fn);
                return dupOut("<error:invalid args json: " + takeExceptionText(h->ctx) + ">");
            }
            if (JS_IsArray(parsed)) {
                JSValue lenVal = JS_GetPropertyStr(h->ctx, parsed, "length");
                int32_t len = 0;
                JS_ToInt32(h->ctx, &len, lenVal);
                JS_FreeValue(h->ctx, lenVal);
                for (int32_t i = 0; i < len; ++i) {
                    argv.push_back(JS_GetPropertyUint32(h->ctx, parsed, static_cast<uint32_t>(i)));
                }
            } else {
                argv.push_back(parsed);
                parsed = JS_UNDEFINED;
            }
            JS_FreeValue(h->ctx, parsed);
        }

        JSValue result = JS_Call(h->ctx, fn, JS_UNDEFINED,
                                 static_cast<int>(argv.size()),
                                 argv.empty() ? nullptr : argv.data());
        for (JSValue &v : argv) {
            JS_FreeValue(h->ctx, v);
        }
        JS_FreeValue(h->ctx, fn);

        if (JS_IsException(result)) {
            return dupOut("<error:" + takeExceptionText(h->ctx) + ">");
        }

        if (!JS_IsPromise(result)) {
            std::string json;
            bool serialized = stringifyToJson(h->ctx, result, json);
            JS_FreeValue(h->ctx, result);
            if (!serialized) {
                return dupOut("<error:result is not serializable: " + takeExceptionText(h->ctx) + ">");
            }
            return dupOut(json);
        }

        pendingPromise = result;
        if (!keyStr.empty()) {
            h->inflightCalls[keyStr] = JS_DupValue(h->ctx, pendingPromise);
        }
    }

    bool settled = false;
    JSValue resolved = awaitPromise(h->rt, h->ctx, pendingPromise, settled);
    JS_FreeValue(h->ctx, pendingPromise);

    if (JS_IsException(resolved)) {
        if (!keyStr.empty()) {
            auto inflight = h->inflightCalls.find(keyStr);
            if (inflight != h->inflightCalls.end()) {
                JS_FreeValue(h->ctx, inflight->second);
                h->inflightCalls.erase(inflight);
            }
        }
        return dupOut("<error:" + takeExceptionText(h->ctx) + ">");
    }

    if (!settled) {
        JS_FreeValue(h->ctx, resolved);
        return dupOut("<pending>");
    }

    if (!keyStr.empty()) {
        auto inflight = h->inflightCalls.find(keyStr);
        if (inflight != h->inflightCalls.end()) {
            JS_FreeValue(h->ctx, inflight->second);
            h->inflightCalls.erase(inflight);
        }
    }

    std::string json;
    bool serialized = stringifyToJson(h->ctx, resolved, json);
    JS_FreeValue(h->ctx, resolved);
    if (!serialized) {
        return dupOut("<error:result is not serializable: " + takeExceptionText(h->ctx) + ">");
    }
    return dupOut(json);
}
