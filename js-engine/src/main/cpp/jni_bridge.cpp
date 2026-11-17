#include <jni.h>
#include <android/log.h>

#include <cstdlib>
#include <cstring>
#include <atomic>
#include <string>
#include <utility>
#include <vector>
#include <unordered_map>

#include "quickjs.h"

#define LOG_TAG "LifeBoardJS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/**
 * 模块源码表由 Kotlin 侧注入：模块名 → 源码文本。
 *
 * 为什么不从文件系统读：
 * JS 插件的模块要靠宿主从 .jsplugin（ZIP）里解出来，路径语义由宿主管；
 * 更关键的是 iOS 上拿不到可随意读写的路径。把源码作为字符串传进来，
 * 引擎只负责编译执行，与平台文件系统彻底解耦。
 */
using ModuleTable = std::unordered_map<std::string, std::string>;

struct HostFunctionSlot;

struct RuntimeHandle {
    JSRuntime *rt = nullptr;
    JSContext *ctx = nullptr;
    ModuleTable modules;

    // 当前线程的 JNIEnv，供宿主函数回调 Kotlin 时使用
    JNIEnv *env = nullptr;

    // Kotlin 侧 dispatcher 的全局引用。所有宿主函数共享同一个 dispatcher 对象，
    // 因此只持有一份引用，避免重复 DeleteGlobalRef 触发 JVM 中止。
    jobject dispatcher = nullptr;

    // 宿主注入给 JS 的函数表；下标即 JS_NewCFunctionMagic 的 magic
    std::vector<HostFunctionSlot> hostFunctions;

    // 等待宿主异步完成的 Promise resolver：token → (resolve, reject)。
    // token 由宿主生成，用于把异步结果定向投递给对应的 Promise。
    std::unordered_map<std::string, std::pair<JSValue, JSValue>> pendingResolvers;

    // 已执行过的模块句柄：模块名 → JSValue(JS_TAG_MODULE)。
    // 调用导出函数时必须复用这里的实例，重新编译会得到未实例化的新对象，
    // 对它取命名空间会崩溃。
    std::unordered_map<std::string, JSValue> executedModules;

    // 挂起中的函数调用：调用键 → 该次调用的 Promise。
    //
    // 为什么需要：插件函数 `async fetch()` 里有 await 时，callFunction 会先返回 pending；
    // 宿主 resolve 之后必须继续等**同一个** Promise，而不是重新调用一遍函数
    // （重新调用会重跑整个函数体，产生新的请求）。
    //
    // 键必须由 Kotlin 侧保证唯一（每次调用一个 invocationId）：
    // 若用「模块#函数」当键，一次调用落定前的下一次调用会复用旧 Promise，
    // 拿到上一次的结果。
    std::unordered_map<std::string, JSValue> inflightCalls;

    // 当前正在执行的模块路径，用于给 import.meta 提供 url 字段
    std::string currentModuleUrl;

    // 中断标志。由宿主线程置位，JS 解释器在安全点回调里检查。
    //
    // QuickJS 只能在创建它的线程上执行，宿主无法从外部打断一个死循环；
    // 唯一可行的方式是 JS_SetInterruptHandler：
    // 解释器每执行若干条指令就会同步调用回调，回调里返回非 0 即抛异常终止执行。
    // 标志用 std::atomic 保证跨线程可见。
    std::atomic<bool> interruptRequested{false};
};

/**
 * 解释器中断回调。返回非 0 表示「立刻中止当前脚本执行」。
 *
 * 只在创建运行时的线程上被调用（QuickJS 保证），因此这里只读原子标志，
 * 不碰任何 JSValue。
 */
int interruptHandler(JSRuntime * /*rt*/, void *opaque) {
    auto *h = static_cast<RuntimeHandle *>(opaque);
    return (h != nullptr && h->interruptRequested.load(std::memory_order_relaxed)) ? 1 : 0;
}

RuntimeHandle *fromHandle(jlong handle) {
    return reinterpret_cast<RuntimeHandle *>(handle);
}

/**
 * 把 JSValue 转成可直接返回给 Kotlin 的字符串描述，异常时返回错误标记。
 */
std::string valueToDebugString(JSContext *ctx, JSValueConst value) {
    const char *str = JS_ToCString(ctx, value);
    if (!str) {
        return "<unstringifiable>";
    }
    std::string out(str);
    JS_FreeCString(ctx, str);
    return out;
}

/**
 * 取当前上下文的异常，拼成纯文本错误信息（不带 JS 堆栈，宿主另有日志）。
 * 调用后异常即被清除。
 */
std::string takeExceptionText(JSContext *ctx) {
    JSValue exception = JS_GetException(ctx);
    std::string text = valueToDebugString(ctx, exception);
    JS_FreeValue(ctx, exception);
    return text;
}

/**
 * 把 JSValue 序列化成 JSON 文本，供跨语言传值使用。
 * 序列化失败（循环引用等）时返回 ok=false。
 */
bool stringifyToJson(JSContext *ctx, JSValueConst value, std::string &out) {
    JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) {
        return false;
    }
    if (JS_IsUndefined(json)) {
        // undefined / 函数 / symbol 无法序列化，统一按 null 处理
        out = "null";
        JS_FreeValue(ctx, json);
        return true;
    }
    out = valueToDebugString(ctx, json);
    JS_FreeValue(ctx, json);
    return true;
}

/**
 * 把 JSON 文本解析为 JSValue。
 * 传入空串或 "null" 时返回 JS_NULL，便于 Kotlin 侧省略参数。
 */
JSValue parseJson(JSContext *ctx, const std::string &text) {
    if (text.empty() || text == "null") {
        return JS_NULL;
    }
    return JS_ParseJSON(ctx, text.c_str(), text.size(), "<json>");
}

/**
 * 等 Promise 落定，返回其结果值。
 *
 * QuickJS 没有阻塞等待机制，只能反复驱动 job queue 并检查状态。
 * 这里加一个迭代上限，防止插件返回永不落定的 Promise 把线程挂死。
 *
 * @param settled 输出：Promise 是否已完成（含 rejected）
 * @return 结果值；pending 超时时返回 JS_UNDEFINED 并置 settled=false
 */
JSValue awaitPromise(JSRuntime *rt, JSContext *ctx, JSValueConst promise, bool &settled) {
    // 上限取较大值：真实网络请求需要等 Kotlin 侧回调投递 job，
    // 而回调不是本线程同步产生的，这里只是防止 JS 自身死等。
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
            // 把 rejection 转成异常状态，交由调用方统一处理
            JS_Throw(ctx, reason);
            return JS_EXCEPTION;
        }
        if (!JS_IsJobPending(rt)) {
            // 没有待执行 job 却仍 pending：说明它在等外部回调（如 Kotlin HTTP）。
            // 直接返回 pending，由 Kotlin 层决定是继续等待还是超时。
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

/**
 * 宿主注入函数的 C 侧桩。
 *
 * JS 调用 ctx.http.get(...) 时先落到这里，通过 JNI 反向调用 Kotlin 侧的
 * HostFunctionDispatcher；Kotlin 返回 JSON 结果后再包成 JSValue 返回给 JS。
 *
 * 返回值约定：
 * - Kotlin 返回普通 JSON 文本 → 解析后作为同步返回值
 * - Kotlin 返回特殊标记 @pending → 返回一个 pending Promise，
 *   由 Kotlin 在异步操作完成后调用 nativeResolvePromise 落地
 * - Kotlin 返回 <error:...> → 抛出 JS 异常
 */
struct HostFunctionSlot {
    std::string name;
    jmethodID invokeMethod = nullptr;
};

JSValue hostFunctionThunk(JSContext *ctx, JSValueConst /*thisVal*/,
                          int argc, JSValueConst *argv, int magic);

/**
 * 在 JS 全局对象上注册一个宿主函数。
 *
 * 挂到 globalThis 上的路径名可以是点分的，如 "ctx.http.get"，
 * 会自动逐级创建中间对象。这样插件里写 ctx.http.get(...) 即可，
 * 不必由插件自己拼装命名空间。
 *
 * @param globalName 形如 "ctx.credentials.get"
 * @param magic      宿主函数表下标
 */
void defineGlobalFunction(JSContext *ctx, const std::string &globalName, int magic) {
    // 逐级创建命名空间
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
    if (!h->dispatcher) {
        return JS_ThrowInternalError(ctx, "host function '%s' not bound", slot.name.c_str());
    }

    // 参数统一序列化为 JSON 数组文本传给 Kotlin
    JSValue arr = JS_NewArray(ctx);
    for (int i = 0; i < argc; ++i) {
        JS_SetPropertyUint32(ctx, arr, static_cast<uint32_t>(i),
                             JS_DupValue(ctx, argv[i]));
    }
    std::string argsJson;
    if (!stringifyToJson(ctx, arr, argsJson)) {
        JS_FreeValue(ctx, arr);
        return JS_ThrowInternalError(ctx, "failed to serialize host call args");
    }
    JS_FreeValue(ctx, arr);

    JNIEnv *env = h->env;
    if (!env) {
        return JS_ThrowInternalError(ctx, "no JNIEnv available");
    }

    jstring jName = env->NewStringUTF(slot.name.c_str());
    jstring jArgs = env->NewStringUTF(argsJson.c_str());
    auto jResult = static_cast<jstring>(env->CallObjectMethod(
            h->dispatcher, slot.invokeMethod, jName, jArgs));
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jArgs);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JS_ThrowInternalError(ctx, "host function '%s' threw", slot.name.c_str());
    }
    if (!jResult) {
        return JS_ThrowInternalError(ctx, "host function '%s' returned null", slot.name.c_str());
    }

    const char *resultChars = env->GetStringUTFChars(jResult, nullptr);
    std::string resultText(resultChars);
    env->ReleaseStringUTFChars(jResult, resultChars);
    env->DeleteLocalRef(jResult);

    if (resultText.rfind("<error:", 0) == 0) {
        std::string msg = resultText.substr(7);
        if (!msg.empty() && msg.back() == '>') msg.pop_back();
        return JS_ThrowInternalError(ctx, "%s", msg.c_str());
    }

    if (resultText.rfind("<pending:", 0) == 0) {
        // 宿主需要异步完成：宿主返回 <pending:token>，其中 token 由宿主生成。
        // 这里把 Promise 的 resolver 按 token 存起来，等宿主稍后
        // 调用 nativeResolvePromise(token, ...) 落地。
        std::string token = resultText.substr(9);
        if (!token.empty() && token.back() == '>') token.pop_back();

        JSValue resolving[2];
        JSValue promise = JS_NewPromiseCapability(ctx, resolving);

        auto existing = h->pendingResolvers.find(token);
        if (existing != h->pendingResolvers.end()) {
            // 同一 token 重复登记：释放旧的，避免泄漏
            JS_FreeValue(ctx, existing->second.first);
            JS_FreeValue(ctx, existing->second.second);
        }
        h->pendingResolvers[token] =
                std::make_pair(resolving[0], resolving[1]);
        return promise;
    }

    if (resultText == "<pending>") {
        // 未带 token 的旧约定：无法被定向 resolve，直接报错以便尽早暴露
        return JS_ThrowInternalError(
                ctx, "host function '%s' returned <pending> without token",
                slot.name.c_str());
    }

    JSValue parsed = JS_ParseJSON(ctx, resultText.c_str(), resultText.size(), "<host-result>");
    if (JS_IsException(parsed)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        // 解析失败时按字符串返回，避免宿主必须保证 JSON
        return JS_NewString(ctx, resultText.c_str());
    }
    return parsed;
}

/**
 * 给模块补上 import.meta。QuickJS 本身不会自动创建它，
 * 不注入的话插件里写 import.meta.url 会直接抛 ReferenceError。
 * 这里只提供 url 一个字段——插件能拿到的其它信息应当由 ctx 注入。
 */
int setImportMeta(JSContext *ctx, JSValueConst funcVal, const std::string &url) {
    if (JS_VALUE_GET_TAG(funcVal) != JS_TAG_MODULE) {
        return -1;
    }
    auto *m = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(funcVal));
    JSValue metaObj = JS_GetImportMeta(ctx, m);
    if (JS_IsException(metaObj)) {
        return -1;
    }
    JSValue urlVal = JS_NewString(ctx, url.c_str());
    JS_SetPropertyStr(ctx, metaObj, "url", urlVal);
    JS_FreeValue(ctx, metaObj);
    return 0;
}

/**
 * 模块加载器：按模块名从注入的表里取源码，编译为模块对象。
 *
 * 注意 JS_EVAL_FLAG_COMPILE_ONLY：这里只编译不执行，
 * 执行由 QuickJS 在依赖图解析完成后统一驱动（模块必须等依赖都就绪）。
 */
JSModuleDef *moduleLoader(JSContext *ctx, const char *moduleName, void *opaque) {
    auto *rt = static_cast<RuntimeHandle *>(opaque);
    if (!rt) {
        JS_ThrowReferenceError(ctx, "module loader has no runtime");
        return nullptr;
    }

    auto it = rt->modules.find(moduleName);
    if (it == rt->modules.end()) {
        JS_ThrowReferenceError(ctx, "module not found: '%s'", moduleName);
        return nullptr;
    }

    const std::string &source = it->second;
    JSValue funcVal = JS_Eval(
            ctx,
            source.c_str(),
            source.size(),
            moduleName,
            JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);

    if (JS_IsException(funcVal)) {
        return nullptr;
    }

    if (setImportMeta(ctx, funcVal, std::string("lifeboard://") + moduleName) < 0) {
        JS_FreeValue(ctx, funcVal);
        return nullptr;
    }

    // 模块已被引擎引用，这里释放的是我们的句柄
    auto *m = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(funcVal));
    JS_FreeValue(ctx, funcVal);
    return m;
}

/**
 * 驱动 QuickJS 的 job queue，让 Promise 的 then/callback 真正执行。
 *
 * 返回执行的 job 数量。-1 表示有 job 抛出异常。
 * QuickJS 单线程模型下，所有微任务都靠这个循环推进——
 * 不调用它，所有 await 都会永远挂起。
 */
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

}  // namespace

extern "C" {

/** 释放宿主函数持有的全局引用，并清空已执行模块句柄。 */
void releaseHostFunctions(JNIEnv *env, RuntimeHandle *h) {
    // dispatcher 是所有宿主函数共享的一份全局引用，只能释放一次
    if (h->dispatcher) {
        env->DeleteGlobalRef(h->dispatcher);
        h->dispatcher = nullptr;
    }
    h->hostFunctions.clear();

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
}

/**
 * 在新建的 ctx 上重新挂载已注册的宿主函数。
 *
 * setModules 会重建 ctx（为了丢弃模块缓存），而全局对象是 ctx 级的，
 * 宿主函数必须重新定义，否则换模块后 ctx.http 之类的能力会消失。
 */
void reinstallHostFunctions(RuntimeHandle *h) {
    if (!h->ctx) {
        return;
    }
    JS_SetContextOpaque(h->ctx, h);
    for (size_t i = 0; i < h->hostFunctions.size(); ++i) {
        defineGlobalFunction(h->ctx, h->hostFunctions[i].name, static_cast<int>(i));
    }
}

JNIEXPORT jlong JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeCreate(JNIEnv *env, jobject /*thiz*/) {
    auto *handle = new RuntimeHandle();

    handle->rt = JS_NewRuntime();
    if (!handle->rt) {
        LOGE("JS_NewRuntime failed");
        delete handle;
        return 0;
    }

    // 限制单实例内存上限，避免插件写死循环把宿主撑爆。
    // 64MB 对数据源插件远远够用（典型插件脚本 < 100KB）。
    JS_SetMemoryLimit(handle->rt, 64 * 1024 * 1024);

    // 栈深度限制，防递归爆栈穿透到宿主。
    JS_SetMaxStackSize(handle->rt, 1024 * 1024);

    // 注册模块加载器，让 import 能工作
    JS_SetModuleLoaderFunc(handle->rt, nullptr, moduleLoader, handle);

    // 中断兜底：插件里的死循环（如 for(;;){}）没有任何 await，
    // 会把这个单线程永久占住，扫描、卸载、退出全部挂死。
    // 中断标志由宿主线程置位，解释器在安全点同步检查。
    JS_SetInterruptHandler(handle->rt, interruptHandler, handle);

    handle->ctx = JS_NewContext(handle->rt);
    if (!handle->ctx) {
        LOGE("JS_NewContext failed");
        JS_FreeRuntime(handle->rt);
        delete handle;
        return 0;
    }

    // 宿主函数回调时需要拿回 RuntimeHandle
    JS_SetContextOpaque(handle->ctx, handle);

    LOGI("runtime created");
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeDestroy(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = fromHandle(handle);
    if (!h) {
        return;
    }
    releaseHostFunctions(env, h);
    if (h->ctx) {
        JS_FreeContext(h->ctx);
    }
    if (h->rt) {
        JS_FreeRuntime(h->rt);
    }
    delete h;
    LOGI("runtime destroyed");
}

/**
 * 注册宿主函数。
 *
 * @param names      JS 侧的全局路径名，如 ["ctx.http.get", "ctx.log.d"]
 * @param dispatcher Kotlin 侧实现 HostFunctionDispatcher 的对象
 *
 * dispatcher 通过全局引用持有，生命周期与运行时一致，在 nativeDestroy 中释放。
 */
JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeRegisterHostFunctions(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray names, jobject dispatcher) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx || !dispatcher) {
        return;
    }

    // 记录 JNIEnv 供回调使用。注意：JNIEnv 是线程相关的，
    // 这里保存的是创建/调用时的 env，跨线程调用需要 AttachCurrentThread。
    h->env = env;

    jclass dispatcherClass = env->GetObjectClass(dispatcher);
    jmethodID invoke = env->GetMethodID(dispatcherClass, "invoke",
                                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!invoke) {
        LOGE("dispatcher.invoke(String,String)String not found");
        env->ExceptionClear();
        env->DeleteLocalRef(dispatcherClass);
        return;
    }

    // 重复注册时先释放旧的全局引用，保证只有一份
    if (h->dispatcher) {
        env->DeleteGlobalRef(h->dispatcher);
        h->dispatcher = nullptr;
    }
    h->dispatcher = env->NewGlobalRef(dispatcher);

    jsize count = env->GetArrayLength(names);
    for (jsize i = 0; i < count; ++i) {
        auto jName = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        const char *nameChars = env->GetStringUTFChars(jName, nullptr);
        std::string name(nameChars);
        env->ReleaseStringUTFChars(jName, nameChars);
        env->DeleteLocalRef(jName);

        HostFunctionSlot slot;
        slot.name = name;
        slot.invokeMethod = invoke;
        h->hostFunctions.push_back(slot);

        defineGlobalFunction(h->ctx, name,
                             static_cast<int>(h->hostFunctions.size()) - 1);
    }

    env->DeleteLocalRef(dispatcherClass);
    LOGI("registered %d host function(s)", count);
}

/**
 * 执行一段脚本（global 作用域），返回结果的字符串形式。
 * 失败时返回 "<error:...>"，由 Kotlin 侧判定并抛异常。
 */
JNIEXPORT jstring JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeEvaluate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring code, jstring filename) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return env->NewStringUTF("<error:no-runtime>");
    }

    const char *codeChars = env->GetStringUTFChars(code, nullptr);
    const char *filenameChars = env->GetStringUTFChars(filename, nullptr);

    JSValue result = JS_Eval(
            h->ctx,
            codeChars,
            strlen(codeChars),
            filenameChars,
            JS_EVAL_TYPE_GLOBAL);

    std::string out;
    if (JS_IsException(result)) {
        JSValue exception = JS_GetException(h->ctx);
        out = "<error:" + valueToDebugString(h->ctx, exception) + ">";
        JS_FreeValue(h->ctx, exception);
    } else {
        out = valueToDebugString(h->ctx, result);
    }
    JS_FreeValue(h->ctx, result);

    env->ReleaseStringUTFChars(code, codeChars);
    env->ReleaseStringUTFChars(filename, filenameChars);

    // 顶层代码里可能出现 await（模块顶层 await），需要把微任务跑完
    drainJobs(h->rt);

    return env->NewStringUTF(out.c_str());
}

/**
 * 注册一批模块源码。必须在执行任何 import 之前调用。
 *
 * 每次调用都会重建 JSContext，而不是只替换源码表。原因：
 * QuickJS 把已加载模块缓存在 ctx->loaded_modules 里，且按模块名匹配；
 * 只换表不换 ctx 的话，重新注册后的 main.js 会命中旧模块，拿到过期的导出。
 * 重建 ctx 是让「换一批模块」语义干净的唯一可靠方式。
 *
 * @param names  模块名数组，如 ["main.js", "lib/crypto.js"]
 * @param sources 与 names 等长的源码数组
 */
JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeSetModules(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray names, jobjectArray sources) {
    auto *h = fromHandle(handle);
    if (!h || !h->rt) {
        return;
    }

    jsize count = env->GetArrayLength(names);
    h->modules.clear();
    h->modules.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto nameStr = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto sourceStr = static_cast<jstring>(env->GetObjectArrayElement(sources, i));

        const char *nameChars = env->GetStringUTFChars(nameStr, nullptr);
        const char *sourceChars = env->GetStringUTFChars(sourceStr, nullptr);

        h->modules[std::string(nameChars)] = std::string(sourceChars);

        env->ReleaseStringUTFChars(nameStr, nameChars);
        env->ReleaseStringUTFChars(sourceStr, sourceChars);
        env->DeleteLocalRef(nameStr);
        env->DeleteLocalRef(sourceStr);
    }

    // 清空上下文以丢弃已加载模块缓存。
    // 已执行模块、挂起调用与未落地的 Promise resolver 都持有**旧 ctx** 的句柄，
    // 必须先在旧 ctx 上释放再销毁 ctx，否则既泄漏又会留下悬挂的 token——
    // 之后 OkHttp 回调带着旧 token 进来，会命中一张已经无效的表。
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

    if (h->ctx) {
        JS_FreeContext(h->ctx);
    }
    h->ctx = JS_NewContext(h->rt);
    if (!h->ctx) {
        LOGE("JS_NewContext failed while resetting modules");
    } else {
        // 全局对象是 ctx 级的，宿主函数必须在新 ctx 上重新定义
        reinstallHostFunctions(h);
    }

    LOGI("registered %d module(s), context reset", count);
}

/**
 * 取模块命名空间对象。调用方负责 free。
 *
 * 只对已执行过的模块有效：未实例化的模块取命名空间会崩溃。
 * 因此必须从 executedModules 里复用实例，不能重新编译。
 */
JSValue getModuleNamespace(RuntimeHandle *h, const std::string &name) {
    auto execIt = h->executedModules.find(name);
    if (execIt == h->executedModules.end()) {
        JS_ThrowReferenceError(h->ctx, "module not executed yet: '%s'", name.c_str());
        return JS_EXCEPTION;
    }

    auto *moduleDef = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(execIt->second));
    return JS_GetModuleNamespace(h->ctx, moduleDef);
}

/**
 * 以 ES 模块方式执行指定模块，返回其导出名列表（JSON 数组字符串）。
 *
 * 返回值约定：
 * - 成功：如 ["login","fetch","health"]
 * - 失败：以 "<error:" 开头的字符串
 */
JNIEXPORT jstring JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeEvaluateModule(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring moduleName) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return env->NewStringUTF("<error:no-runtime>");
    }

    const char *nameChars = env->GetStringUTFChars(moduleName, nullptr);
    std::string name(nameChars);
    env->ReleaseStringUTFChars(moduleName, nameChars);

    auto it = h->modules.find(name);
    if (it == h->modules.end()) {
        std::string msg = "<error:module not registered: '" + name + "'>";
        return env->NewStringUTF(msg.c_str());
    }

    const std::string &source = it->second;

    // 第一步：编译为模块对象
    JSValue funcVal = JS_Eval(
            h->ctx,
            source.c_str(),
            source.size(),
            name.c_str(),
            JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);

    std::string out;
    if (JS_IsException(funcVal)) {
        JSValue exception = JS_GetException(h->ctx);
        out = "<error:" + valueToDebugString(h->ctx, exception) + ">";
        JS_FreeValue(h->ctx, exception);
        return env->NewStringUTF(out.c_str());
    }

    if (setImportMeta(h->ctx, funcVal, "lifeboard://" + name) < 0) {
        JS_FreeValue(h->ctx, funcVal);
        return env->NewStringUTF("<error:import.meta injection failed>");
    }

    // 第二步：真正执行（会连带解析并执行所有 import 的依赖）
    // JS_EvalFunction 会消费 funcVal，先复制一份作为已执行模块的长期句柄。
    JSValue moduleRef = JS_DupValue(h->ctx, funcVal);
    JSValue result = JS_EvalFunction(h->ctx, funcVal);

    if (JS_IsException(result)) {
        JSValue exception = JS_GetException(h->ctx);
        out = "<error:" + valueToDebugString(h->ctx, exception) + ">";
        JS_FreeValue(h->ctx, exception);
        JS_FreeValue(h->ctx, moduleRef);
        return env->NewStringUTF(out.c_str());
    }
    JS_FreeValue(h->ctx, result);

    // 第三步：跑完微任务，让模块顶层 await 与 Promise 回调落地
    drainJobs(h->rt);

    // 第四步：登记已执行模块，供后续 callFunction 复用
    auto prev = h->executedModules.find(name);
    if (prev != h->executedModules.end()) {
        JS_FreeValue(h->ctx, prev->second);
    }
    h->executedModules[name] = moduleRef;

    // 第五步：取模块命名空间，列出导出名
    out = "[]";
    JSValue ns = JS_GetModuleNamespace(
            h->ctx, static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(moduleRef)));
    if (!JS_IsException(ns)) {
        JSPropertyEnum *tab = nullptr;
        uint32_t len = 0;
        if (JS_GetOwnPropertyNames(
                    h->ctx, &tab, &len, ns,
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

    return env->NewStringUTF(out.c_str());
}

/**
 * 落地一个由宿主异步完成的 Promise。
 *
 * 流程：JS 调 ctx.http.get() → 宿主返回 <pending:token> → JS 拿到 pending Promise
 * → OkHttp 回调完成 → Kotlin 调本函数投递结果 → Promise 落定 → await 继续。
 *
 * resolve 之后会顺带驱动 job queue，让 await 之后的代码在同一调用内推进；
 * 若链上又有新的异步调用，会再次挂起。
 *
 * @param token      与宿主返回的 <pending:token> 一致
 * @param resultJson 成功的 JSON 结果
 * @param errorText  非 null 表示 reject，内容作为错误信息
 * @return 是否找到并落地了对应 Promise
 */
JNIEXPORT jboolean JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeResolvePromise(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jstring token, jstring resultJson, jstring errorText) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return JNI_FALSE;
    }

    const char *tokenChars = env->GetStringUTFChars(token, nullptr);
    std::string tokenStr(tokenChars);
    env->ReleaseStringUTFChars(token, tokenChars);

    auto it = h->pendingResolvers.find(tokenStr);
    if (it == h->pendingResolvers.end()) {
        LOGE("resolvePromise: unknown token '%s'", tokenStr.c_str());
        return JNI_FALSE;
    }

    JSValue resolveFn = it->second.first;
    JSValue rejectFn = it->second.second;
    h->pendingResolvers.erase(it);

    bool isError = errorText != nullptr;
    JSValue arg;

    if (isError) {
        const char *errChars = env->GetStringUTFChars(errorText, nullptr);
        // 必须造 Error 对象，不能传裸字符串。
        //
        // 传字符串时 JS 侧 catch(e) 拿到的是字符串，`e.message` 是 undefined，
        // 于是插件里 `'失败：' + e.message` 只会拼出「失败：undefined」，
        // 真正的失败原因（超时、连接被拒等）完全看不到。
        // 这是排查线上问题时最要命的一种信息丢失。
        arg = JS_NewError(h->ctx);
        if (!JS_IsException(arg)) {
            JS_SetPropertyStr(h->ctx, arg, "message", JS_NewString(h->ctx, errChars));
        } else {
            // 造 Error 失败时退回字符串，至少别让 Promise 悬着
            JS_FreeValue(h->ctx, JS_GetException(h->ctx));
            arg = JS_NewString(h->ctx, errChars);
        }
        env->ReleaseStringUTFChars(errorText, errChars);
    } else {
        const char *resChars = env->GetStringUTFChars(resultJson, nullptr);
        std::string resText(resChars);
        env->ReleaseStringUTFChars(resultJson, resChars);

        if (resText.empty()) {
            arg = JS_UNDEFINED;
        } else {
            arg = JS_ParseJSON(h->ctx, resText.c_str(), resText.size(), "<resolve>");
            if (JS_IsException(arg)) {
                // 非法 JSON 退回字符串，避免宿主必须保证格式
                JS_FreeValue(h->ctx, JS_GetException(h->ctx));
                arg = JS_NewString(h->ctx, resText.c_str());
            }
        }
    }

    JSValue callResult = JS_Call(
            h->ctx, isError ? rejectFn : resolveFn, JS_UNDEFINED, 1, &arg);

    JS_FreeValue(h->ctx, arg);
    JS_FreeValue(h->ctx, resolveFn);
    JS_FreeValue(h->ctx, rejectFn);

    bool ok = !JS_IsException(callResult);
    if (!ok) {
        LOGE("resolvePromise: callback threw: %s",
             takeExceptionText(h->ctx).c_str());
    }
    JS_FreeValue(h->ctx, callResult);

    // 注意：这里刻意不驱动 job queue。
    //
    // 若在 resolve 里 drainJobs，而 resolve 又被宿主函数在自身执行期间
    // 同步调用，就会重入正在运行的 JS 栈，表现为 "Maximum call stack size exceeded"。
    // 真实的异步回调（OkHttp 完成）天然不在 JS 调用栈内，因此驱动交给
    // 显式调用方：resolve 之后由宿主再调一次 drainJobs()。

    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 驱动 job queue，让已 resolve 的 Promise 继续推进。
 *
 * 与 resolvePromise 分离是刻意的：resolve 只负责投递结果，
 * 推进由调用方在安全时机触发，避免重入。
 *
 * @return 执行的 job 数量；-1 表示有 job 抛出异常
 */
JNIEXPORT jint JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeDrainJobs(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = fromHandle(handle);
    if (!h || !h->rt) {
        return -1;
    }
    return drainJobs(h->rt);
}

/**
 * 丢弃某个调用键上挂起的 Promise。
 *
 * 使用场景：宿主等待超时或用户取消后，不再关心这次调用的结果。
 * 不清理的话：
 * - Promise 句柄会一直挂在 inflightCalls 里泄漏；
 * - 下次用同一个键调用时（若宿主复用了键）会拿到一个永远不会落定的旧 Promise。
 *
 * @return 是否真的移除了记录
 */
JNIEXPORT jboolean JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeDropCall(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring callKey) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx || !callKey) {
        return JNI_FALSE;
    }

    const char *keyChars = env->GetStringUTFChars(callKey, nullptr);
    std::string keyStr(keyChars);
    env->ReleaseStringUTFChars(callKey, keyChars);

    auto it = h->inflightCalls.find(keyStr);
    if (it == h->inflightCalls.end()) {
        return JNI_FALSE;
    }
    JS_FreeValue(h->ctx, it->second);
    h->inflightCalls.erase(it);
    return JNI_TRUE;
}

/**
 * 请求中断当前运行时的脚本执行。
 *
 * 可以从**任意线程**调用：它只置一个原子标志，不碰任何 JSValue。
 * 解释器在下一次安全点检查到标志后会抛异常，正在执行的死循环随即退出。
 *
 * 语义是「一次性」：命中后引擎自动清除标志，不影响后续调用。
 */
JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeRequestInterrupt(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *h = fromHandle(handle);
    if (h) {
        h->interruptRequested.store(true, std::memory_order_relaxed);
    }
}

/**
 * 清除中断标志。每次宿主发起调用前清一次，避免上一次的残留把本次直接打断。
 */
JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeClearInterrupt(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *h = fromHandle(handle);
    if (h) {
        h->interruptRequested.store(false, std::memory_order_relaxed);
    }
}

/**
 * 强制 GC，插件卸载后回收内存。 */
JNIEXPORT void JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeGc(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = fromHandle(handle);
    if (h && h->rt) {
        JS_RunGC(h->rt);
    }
}

/**
 * 调用 JS 模块导出的函数。
 *
 * 参数与返回值统一走 JSON 文本，原因：
 * - 跨语言类型映射只需维护一处（JS 的 JSON ↔ Kotlin 的 JSON 解析）
 * - 插件返回值本来就是 JSON 友好的数据结构（PluginMetric、UiBlock 都是纯数据）
 *
 * 返回约定（Kotlin 侧据此判定成功/失败）：
 * - 成功：JSON 文本，如 {"metricKey":"steps","value":5000}
 * - 失败：以 "<error:" 开头的字符串
 *
 * 异步函数：若返回值是 Promise，会在同一线程内驱动 job queue 等它落定。
 * 若 Promise 依赖 Kotlin 侧的外部回调（如 HTTP），本轮返回 "<pending>"。
 *
 * @param callKey 调用标识。传非空值时，若该键已有挂起中的 Promise，
 *                直接沿用那个 Promise 继续等待，**不会重新执行函数体**。
 *                这是必须的：重新执行会让插件重跑整个 fetch，重复发请求。
 */
JNIEXPORT jstring JNICALL
Java_com_lifeboard_jsengine_JsNativeBridge_nativeCallFunction(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle,
        jstring moduleName,
        jstring functionName,
        jstring argsJson,
        jstring callKey) {
    auto *h = fromHandle(handle);
    if (!h || !h->ctx) {
        return env->NewStringUTF("<error:no-runtime>");
    }

    const char *modChars = env->GetStringUTFChars(moduleName, nullptr);
    const char *fnChars = env->GetStringUTFChars(functionName, nullptr);
    const char *argsChars = env->GetStringUTFChars(argsJson, nullptr);
    const char *keyChars = callKey ? env->GetStringUTFChars(callKey, nullptr) : nullptr;

    std::string modName(modChars);
    std::string fnName(fnChars);
    std::string argsText(argsChars);
    std::string keyStr(keyChars ? keyChars : "");

    env->ReleaseStringUTFChars(moduleName, modChars);
    env->ReleaseStringUTFChars(functionName, fnChars);
    env->ReleaseStringUTFChars(argsJson, argsChars);
    if (callKey) {
        env->ReleaseStringUTFChars(callKey, keyChars);
    }

    std::string out;

    // 每次调用前清一次中断标志：上一轮的超时中断可能残留，
    // 不清的话本次调用会在第一条指令处就被打断。
    h->interruptRequested.store(false, std::memory_order_relaxed);

    // 已有挂起中的同键调用：复用它，不重新执行函数
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
            out = "<error:" + takeExceptionText(h->ctx) + ">";
            return env->NewStringUTF(out.c_str());
        }

        JSValue fn = JS_GetPropertyStr(h->ctx, ns, fnName.c_str());
        JS_FreeValue(h->ctx, ns);

        if (JS_IsException(fn)) {
            out = "<error:" + takeExceptionText(h->ctx) + ">";
            return env->NewStringUTF(out.c_str());
        }
        if (!JS_IsFunction(h->ctx, fn)) {
            JS_FreeValue(h->ctx, fn);
            out = "<error:not a function: '" + fnName + "'>";
            return env->NewStringUTF(out.c_str());
        }

        // 参数按 JSON 数组传入，元素个数不限
        std::vector<JSValue> argv;
        if (!argsText.empty() && argsText != "null" && argsText != "[]") {
            JSValue parsed = JS_ParseJSON(h->ctx, argsText.c_str(), argsText.size(), "<args>");
            if (JS_IsException(parsed)) {
                JS_FreeValue(h->ctx, fn);
                out = "<error:invalid args json: " + takeExceptionText(h->ctx) + ">";
                return env->NewStringUTF(out.c_str());
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
                // 非数组参数按单参数处理，避免调用方必须包一层 []
                argv.push_back(parsed);
                parsed = JS_UNDEFINED;
            }
            JS_FreeValue(h->ctx, parsed);
        }

        JSValue result = JS_Call(
                h->ctx, fn, JS_UNDEFINED,
                static_cast<int>(argv.size()),
                argv.empty() ? nullptr : argv.data());

        for (JSValue &v : argv) {
            JS_FreeValue(h->ctx, v);
        }
        JS_FreeValue(h->ctx, fn);

        if (JS_IsException(result)) {
            out = "<error:" + takeExceptionText(h->ctx) + ">";
            return env->NewStringUTF(out.c_str());
        }

        // 非 Promise 直接返回
        if (!JS_IsPromise(result)) {
            std::string json;
            bool serialized = stringifyToJson(h->ctx, result, json);
            JS_FreeValue(h->ctx, result);
            if (!serialized) {
                out = "<error:result is not serializable: " + takeExceptionText(h->ctx) + ">";
                return env->NewStringUTF(out.c_str());
            }
            return env->NewStringUTF(json.c_str());
        }

        pendingPromise = result;
        // 登记挂起调用，供后续同键调用复用
        if (!keyStr.empty()) {
            h->inflightCalls[keyStr] = JS_DupValue(h->ctx, pendingPromise);
        }
    }

    // 等 Promise 落定
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
        out = "<error:" + takeExceptionText(h->ctx) + ">";
        return env->NewStringUTF(out.c_str());
    }

    if (!settled) {
        // 仍在等 Kotlin 侧回调：调用状态保留在 inflightCalls 里
        JS_FreeValue(h->ctx, resolved);
        return env->NewStringUTF("<pending>");
    }

    // 已落定：清理挂起记录
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
        out = "<error:result is not serializable: " + takeExceptionText(h->ctx) + ">";
        return env->NewStringUTF(out.c_str());
    }
    return env->NewStringUTF(json.c_str());
}

}  // extern "C"
