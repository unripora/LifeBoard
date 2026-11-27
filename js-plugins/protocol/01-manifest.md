# 01 · manifest 与包结构

> 权威来源：`shared/src/jvmMain/.../plugin/js/JsPluginManifest.kt`

## 包结构

`.jsplugin` 是一个 ZIP 包，宿主只读取两类文件：

| 文件 | 必需 | 说明 |
|---|---|---|
| `manifest.json` | 是 | 元数据；不参与内容哈希 |
| `*.js` | 至少入口一个 | 只有 `.js` 会被注册为模块，其余资源不进内存也不落盘 |

- 单个包解压后总字节上限 **16 MiB**，条目数上限 **512**（防 zip bomb）
- 条目路径不得为绝对路径、不得含 `..` 或 `:`
- 入口路径同样受此约束

## 身份与哈希

| 字段 | 规则 |
|---|---|
| `id` | 必需；反域名式；**不得含 `:`**（它是插件 key 的分隔符） |
| 内容哈希 | 包内除 `manifest.json` 外的文件按文件名排序后 SHA-256；作为签名指纹 |
| `sha256` | 可选；发布者声明的哈希，与实算不一致即拒载 |

内容哈希**不含 manifest**：改一行描述不该要求用户重新信任。
但入口、指标、凭据槽等运行元数据变化时，宿主会重建实例。

## 字段

### 必需

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 见上 |
| `name` | string | 展示名；空则用 `id` |
| `version` | string | 语义版本 |
| `versionCode` | number | 单调递增；升级判定用 |
| `apiVersion` | number | 按哪一代 `ctx.*` 编写；高于宿主则拒载 |
| `entry` | string | 入口模块，默认 `main.js` |
| `metrics` | array | 指标声明，见下 |
| `credentialSpec` | array | 凭据槽声明 |

### 可选

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `description` / `author` / `homepage` | string | 空 | 展示用 |
| `defaultTabName` / `tabIcon` | string | 空 | 页签展示 |
| `pinnedCaSha256` | string | 空 | 自签 CA 指纹；`"runtime"` 表示运行时提供 |
| `lifecycle` | string | `onDemand` | 见 [02-runtime](./02-runtime.md) |
| `idleTimeoutSec` | number | 300 | 仅 `onDemand` 生效，夹在 `[60, 3600]` |
| `requires` | string[] | 空 | 必需宿主能力；空表示不校验 |

## 校验与拒载

宿主在**信任判定之前**依次校验，任一失败即拒载并给出可读原因：

1. `manifest.json` 是合法 JSON
2. `id` 非空且不含 `:`
3. `entry` 非绝对路径、不含 `..`
4. `lifecycle` 取值合法（拼错**不**退化成默认值，直接拒载）
5. `pinnedCaSha256` 是规范指纹（或 `runtime`）
6. `apiVersion` 不高于宿主
7. `requires` 全部满足（仅当显式声明）
8. 内容哈希与 `sha256` 声明一致

**生命周期为什么拼错要拒载**：作者写了 `resident` 但拼成 `residental`，
静默按 `onDemand` 处理会让"常驻"失效且毫无线索——不如直接报错。

## 指标声明

```json
{ "metricKey": "STEPS", "bucket": "STEPS", "displayName": "步数", "unit": "步", "aggregate": "sum" }
```

- `metricKey` 不得为空、不得含 `:`
- 同一 key 只能声明一次，且量纲唯一
- 量纲（`bucket`）由宿主按 key 反查补齐，插件上报时不指定

## 凭据槽声明

```json
{ "name": "account", "displayName": "账号", "required": true, "secret": false }
```

- `secret: true` 的槽在界面掩码显示；长期秘密（密码、令牌）必须设 true
- 验证码这类一次性短码**不要**设 true：用户要照着抄，掩码只会增加输错率
- 一次性值（如 `otp`）可以不声明为槽位，插件只在该轮登录使用、不落盘
