# QuickJS-ng（第三方源码，已精简）

本目录是第三方 JS 引擎源码，**不是本项目编写**，请勿直接修改。
升级时整目录替换，并更新下方版本号。

## 来源

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/quickjs-ng/quickjs |
| 版本 | `v0.9.0` |
| 提交 | `670492dd342dace0bb7bd6fbfbde8f0bc5651224` |
| 许可证 | MIT（见同目录 `LICENSE`） |
| 引入日期 | 2026-11-19 |

## 为什么只保留部分文件

上游完整仓库约 4 MB、127 个文件，其中大部分是命令行工具、官方一致性测试套件
（`test262/`）、示例与文档，本项目均不使用。这里只保留**编译引擎本体所需的最小闭包**，
避免把数百个永远不会被编译的文件带进版本库。

保留的 17 个文件：

- **源文件（5）**：`quickjs.c`、`libregexp.c`、`libunicode.c`、`libbf.c`、`cutils.c`
- **头文件（11）**：上述源文件的完整包含闭包
- **许可证（1）**：`LICENSE`

被移除的内容包括 `qjs.c` / `qjsc.c` / `quickjs-libc.c`（CLI 与 libc 绑定，会引入
`main()` 与 POSIX 依赖，Android 上无法编译）、`test262/`、`examples/`、`docs/`、
`tests/`、`gen/`、`replay.js` 等。

## 使用方

两端共用这一份源码，各自独立编译：

- `../CMakeLists.txt` — Android，经 `jni_bridge.cpp` 以 JNI 接入
- `../desktop/CMakeLists.txt` — 桌面，经 `desktop_bridge.cpp` 以 C ABI + JNA 接入

## 升级注意

1. 若上游新增了头文件，必须同步补入，否则编译会因找不到 include 而失败。
2. 两端的编译开关不同（桌面侧不开 `CONFIG_ATOMICS`，因为 MSVC 不支持 `_Atomic`），
   详见各自的 `CMakeLists.txt` 注释。
3. `CONFIG_VERSION` 需要与上游 tag 保持一致。
