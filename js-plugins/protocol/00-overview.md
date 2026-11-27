# 00 · 协议总览

> 面向插件作者与宿主维护者的协议分册入口。
> 分册只写**契约事实**；长篇用法与示例仍在 [插件编写指南](../插件编写指南.md)，
> 两者冲突时以分册 + 代码为准。

## 分册导航

| 分册 | 内容 | 权威来源 |
|---|---|---|
| [01-manifest](./01-manifest.md) | 包结构、manifest 字段、校验规则 | `JsPluginManifest.kt` |
| [02-runtime](./02-runtime.md) | 生命周期、租约、无状态规则、限额 | `PluginLifecycle.kt`、`PluginRuntimeManager.kt` |
| [03-host-api](./03-host-api.md) | `ctx.*` 能力清单与分组 | `JsHostFunctions.kt`（单一事实源） |
| [04-contract](./04-contract.md) | 导出函数契约、登录意图、返回形态 | `SourcePlugin.kt`、`plugin-contract.d.ts` |
| [05-errors](./05-errors.md) | 13 种错误码、默认处置、可重试性 | `PluginError.kt` |
| [IMPLEMENTATION](./IMPLEMENTATION.md) | 宿主实现自检清单 | 本文档 + 测试 |

## 版本模型

两套编号，**不要混用**：

| 编号 | 位置 | 含义 | 递增条件 |
|---|---|---|---|
| `apiVersion`（manifest） | 插件声明 | 插件按哪一代 `ctx.*` 编写 | 仅在**破坏性**变更时递增 |
| `requires` 能力名 | manifest | 插件显式依赖的宿主能力 | 新能力出现即可声明 |

新增函数、新增可选字段**不**递增 `apiVersion`；旧插件不会调用它们，行为不变。

## 能力协商（requires）

manifest 可声明 `requires` 数组，宿主在执行入口脚本前校验：

- **缺省不校验**：旧包没有这个字段，强制校验会让它们全部被拒
- **显式声明未知能力 → 拒载**，并说明缺哪项
- 能力名精确匹配，当前可用集合见 [03-host-api §能力集合](./03-host-api.md#能力集合requires-可匹配项)

## 兼容原则

1. 宿主只新增、不删除、不改既有语义；破坏性变更必须递增 `apiVersion`
2. 插件不得假设宿主一定提供新能力——用 `requires` 声明，或运行时探测后降级
3. 卸载钩子**尽力而为**：关键状态必须在业务过程中保存，不能只靠钩子
