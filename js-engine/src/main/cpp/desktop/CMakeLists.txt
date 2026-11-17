cmake_minimum_required(VERSION 3.22)
project(lifeboard-js-desktop C CXX)

# CONFIG_ATOMICS 让 quickjs 使用 C11 的 _Atomic 与 <stdatomic.h>。
# MSVC（含 14.44）不提供 _Atomic 关键字，开启后 quickjs.c 直接编译失败。
# Android 侧是 Clang，支持 _Atomic，所以那边可以开——两端开关不同是
# 编译器差异导致的，不是配置漂移。
# 代价：桌面侧 SharedArrayBuffer 的原子语义退化为普通读写。
# 插件 JS 是单线程执行的（见 JsPluginHost 的串行调度），该退化不影响正确性。
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ---------------------------------------------------------------------------
# 桌面（Windows x86_64）版 QuickJS 动态库
#
# 与 Android 侧共用同一份 quickjs-ng v0.9.0 源码，但桥接方式不同：
#   Android : JNI（jni_bridge.cpp）
#   桌面    : C ABI 扁平导出 + JNA（desktop_bridge.cpp）
#
# 之所以不复用 jni_bridge.cpp：它 <jni.h>/<android/log.h> 强绑定，
# 桌面既没有 JVM 头也没有 log 库。两条桥各自只依赖自己平台的 ABI，
# 上层 Kotlin 通过 JsRuntime / DesktopJsRuntime 分别适配。
# ---------------------------------------------------------------------------

set(QUICKJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../quickjs)

add_library(quickjs STATIC
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/libbf.c
    ${QUICKJS_DIR}/cutils.c
)

target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})

# 与 Android 侧保持一致的编译开关（CONFIG_ATOMICS 除外，见文件头说明）
target_compile_definitions(quickjs PUBLIC
    CONFIG_BIGNUM
    _GNU_SOURCE
)
target_compile_definitions(quickjs PRIVATE
    CONFIG_VERSION=\"2025-09-13\"
)

# ---------------------------------------------------------------------------
# 桌面桥接层：导出纯 C ABI，供 JNA 直接按符号名绑定
# ---------------------------------------------------------------------------
add_library(lifeboard-js SHARED
    desktop_bridge.cpp
)
set_target_properties(lifeboard-js PROPERTIES PREFIX "")

target_include_directories(lifeboard-js PRIVATE ${QUICKJS_DIR})
target_link_libraries(lifeboard-js quickjs)

# 导出所有 lb_ 前缀符号（配合 JNA 的符号名绑定）
if (MSVC)
    # /utf-8：桥接层源码是 UTF-8（含中文注释），而本机代码页为 936，
    # 不加此选项 MSVC 会按 GBK 解读并报 C4819，严重时会把注释尾字节吞进代码。
    target_compile_options(quickjs PRIVATE /O2 /utf-8)
    target_compile_options(lifeboard-js PRIVATE /O2 /utf-8)
    set_target_properties(lifeboard-js PROPERTIES OUTPUT_NAME "lifeboard-js")
else()
    target_compile_options(quickjs PRIVATE -Os -fvisibility=hidden)
    target_compile_options(lifeboard-js PRIVATE -Os -fvisibility=default)
endif()
