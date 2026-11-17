cmake_minimum_required(VERSION 3.22.1)
project(lifeboard-js C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# ---------------------------------------------------------------------------
# QuickJS（quickjs-ng v0.9.0）
#
# 只编译引擎本体需要的 5 个文件。仓库里的 qjs.c / qjsc.c / quickjs-libc.c
# 是命令行工具与 libc 绑定，Android 上用不到，且会引入 main() 与 POSIX 依赖。
# ---------------------------------------------------------------------------
set(QUICKJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)

add_library(quickjs STATIC
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/libbf.c
    ${QUICKJS_DIR}/cutils.c
)

target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})

# Android 的 bionic libc 不提供以下符号，QuickJS 用它们做时间/随机源，
# 需要在编译期显式关掉，改由 JNI 层注入（见 jni_bridge.cpp）。
target_compile_definitions(quickjs PUBLIC
    CONFIG_BIGNUM        # 启用 BigInt / BigFloat，插件做加解密时需要
    CONFIG_ATOMICS       # quickjs-ng 的原子操作支持
    _GNU_SOURCE
)

# 关掉 quickjs-ng 默认开启、但 Android 上不可用的能力
target_compile_definitions(quickjs PRIVATE
    CONFIG_VERSION=\"2025-09-13\"
)

target_link_libraries(quickjs PUBLIC m)

# ---------------------------------------------------------------------------
# JNI 桥接层
# ---------------------------------------------------------------------------
add_library(lifeboard-js SHARED
    jni_bridge.cpp
)

target_include_directories(lifeboard-js PRIVATE ${QUICKJS_DIR})

target_link_libraries(lifeboard-js
    quickjs
    android
    log
)

# 体积控制：release 下 strip 符号，-Os 优先体积（JS 引擎非计算热点）
target_compile_options(quickjs PRIVATE -Os -fvisibility=hidden)
target_compile_options(lifeboard-js PRIVATE -Os -fvisibility=hidden)
