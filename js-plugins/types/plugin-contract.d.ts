/**
 * LifeBoard JS 插件契约的类型声明。
 *
 * 对照宿主实现：
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsSourcePlugin.kt`（调哪些导出函数）
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsTypeConverter.kt`（怎么解析返回值）
 * - `plugin-api/src/commonMain/kotlin/com/lifeboard/plugin/api/*.kt`（契约类型本体）
 *
 * ## 除 `fetch` 外都是可选的
 *
 * 没导出的函数宿主用安全默认值，不会报错。所以声明成可选的导出签名，
 * 由各插件自己决定实现哪些。
 *
 * ## ⚠️ 尾部 `export {}` 不能删
 *
 * 本文件只写 `interface` / `type` 而没有真正的 `export` 语句时，TypeScript
 * 会把它当成**全局脚本**（script），而不是模块。而 `index.d.ts` 用的是
 * `export type { … } from './plugin-contract.js'`——从一个非模块文件
 * re-export 会静默失败，得到的类型全是 `any`：类型检查照样「通过」，
 * 但什么都没检查。一条 `export {}` 就能让它成为模块，语义就回来了。
 */

// 见上方说明：这条语句让本文件成为模块，`index.d.ts` 的 re-export 才生效
export {};

// ---------------------------------------------------------------------------
// 采集结果
// ---------------------------------------------------------------------------

/** 一条指标值。`metricKey` 必须是 manifest 里声明过的。 */
export interface PluginMetric {
    metricKey: string;
    value: number;
}

/**
 * `fetch` 的返回值：裸数组，或带 `status` 的对象。
 *
 * **裸数组 = 成功**，空数组表示「今天确实没数据」。
 *
 * 但裸数组表达不了失败：直接 throw 会被宿主当成普通故障，用户看到的仍是
 * 「今日无数据」，他会去怀疑设备没戴，而不是网络断了。所以失败时返回带
 * `status` 的对象，见 {@link FetchResultObject}。
 */
export type FetchResult = PluginMetric[] | FetchResultObject;

/**
 * 带失败语义的采集结果。
 *
 * | status | 什么时候用 | 宿主怎么处理 |
 * |---|---|---|
 * | `success` | 拿到数据 | 正常落库 |
 * | `empty` | **确定**服务端没有这天的记录 | 不落库，显示「无记录」+ 原因 |
 * | `temporary` | 断网、超时、限流、5xx | 按 retryAfterSeconds 退避，不判定插件坏掉 |
 * | `authRequired` | 凭据失效 / 被踢下线 | 提示重新登录，**不重试** |
 * | `failure` | 接口改了、格式不符 | 记为不健康并展示原因，不重试 |
 *
 * 三条规则：
 * 1. **「不确定有没有」不能报 `empty`**，超时与解析失败一律 `temporary` 或 `failure`；
 * 2. **`status` 拼错等于 `failure`**，不会静默变成「今天没数据」；
 * 3. `retryAfterSeconds` 优先抄服务端的 `Retry-After`。
 *
 * 失败分支可选带 {@link PluginErrorObject}（`error` 字段）说明错误码；
 * 不填时宿主按旧语义推断：`authRequired`→`AUTH_REQUIRED`、
 * `temporary`→`UPSTREAM_ERROR`、`failure`→`INTERNAL_ERROR`。
 */
export type FetchResultObject =
    | { status: 'success'; metrics: PluginMetric[] }
    | { status: 'empty'; reason?: string }
    | { status: 'temporary'; message?: string; retryAfterSeconds?: number; error?: PluginErrorObject }
    | { status: 'authRequired'; message?: string; error?: PluginErrorObject }
    | { status: 'failure'; message?: string; error?: PluginErrorObject };

// ---------------------------------------------------------------------------
// 错误码
// ---------------------------------------------------------------------------

/**
 * 插件错误码（13 种）。
 *
 * 用 `ctx.makeError(code, message, options?)` 构造，或在上报结果里直接携带。
 * 宿主据此决定「默认处置 + 是否可重试」，未知码一律按 `INTERNAL_ERROR`。
 */
export type PluginErrorCode =
    | 'AUTH_REQUIRED'
    | 'AUTH_INVALID'
    | 'AUTH_EXPIRED'
    | 'UPSTREAM_ERROR'
    | 'UPSTREAM_CHANGED'
    | 'RATE_LIMITED'
    | 'UNSUPPORTED'
    | 'INVALID_ARGUMENT'
    | 'STORAGE_FULL'
    | 'RESOURCE_LIMIT'
    | 'INTERRUPTED'
    | 'UNAVAILABLE'
    | 'INTERNAL_ERROR';

/**
 * 结构化错误对象。
 *
 * `message` 会展示给用户，**不得含凭据内容**；`reason` / `limitKind` 仅供日志。
 */
export interface PluginErrorObject {
    code: PluginErrorCode | string;
    message: string;
    /** 建议等待秒数，缺省由宿主决定 */
    retryAfterSec?: number;
    /** 出错的操作标识，便于定位 */
    operation?: string;
    /** 诊断细节，仅入日志 */
    reason?: string;
    /** 限额细分（如 heap / payload / field） */
    limitKind?: string;
}

// ---------------------------------------------------------------------------
// 登录
// ---------------------------------------------------------------------------

/** 一次登录交互中要用户填的一个字段。 */
export interface InputField {
    /**
     * 字段名。与 `credentialSpec` 里的槽位名一致，
     * 或以 `otp` / `captcha` 这类一次性名字出现（不落盘）。
     */
    name: string;

    /** 显示名，留空则用 name。 */
    displayName?: string;

    /**
     * 是否按密码显示（掩码）。
     *
     * 只有**长期秘密**（密码、令牌）应设 true。验证码这类要用户照着抄的
     * 一次性短码必须保持 false——掩码只会增加输错率。
     */
    secret?: boolean;

    /** 输入框提示，如「6 位数字」。 */
    hint?: string;
}

/**
 * `login` 的返回值。
 *
 * 登录流程由插件自己走，宿主只知道「进行到哪一步」和「要用户填什么」。
 * 宿主会反复调用 `login`，插件用模块级变量记住进行到哪一步。
 *
 * **失败必须返回 `failure` 而不是抛异常**：抛出去宿主只会显示
 * 「插件未实现 login」，用户完全看不懂。
 */
export type LoginResult =
    | { type: 'success' }
    | {
          type: 'needInput';
          /** 面向用户的说明，如「需要输入图片验证码」。 */
          prompt: string;
          fields: InputField[];
          /** 验证码图片，`data:image/...;base64,...` 形式。 */
          captchaImage?: string;
      }
    | { type: 'failure'; message: string };

// ---------------------------------------------------------------------------
// 登录意图
// ---------------------------------------------------------------------------

/**
 * 登录交互意图，作为 `login(inputs, options)` 的第二个参数传入。
 *
 * **只有声明了 `loginIntent` 的插件**会收到它；旧插件只看到第一个参数，
 * 行为完全不变（空表单 = 首轮询问/重新开始）。
 *
 * 声明方式：manifest 里加 `"requires": ["contract.loginIntent"]`。
 *
 * | intent | 宿主何时传 | 插件应做什么 |
 * |---|---|---|
 * | `open` | 用户点「登录」打开入口 | **只读取**并展示可恢复的阶段，不提交、不清空 |
 * | `submit` | 用户填完点提交 | 推进当前认证步骤 |
 * | `restart` | 用户点「重新输入账号」 | 清理中间态，返回首轮（账号密码） |
 * | `abandon` | 用户点「放弃登录」 | 清理中间态并结束；应答只确认清理，**不是登录成功** |
 *
 * 关掉表单本身**不会**传 `abandon`——插件应保留中间态，等用户回来继续。
 */
export type LoginIntent = 'open' | 'submit' | 'restart' | 'abandon';

/** `login` 的可选参数；旧宿主不会传，插件必须能处理 `undefined`。 */
export interface LoginOptions {
    intent?: LoginIntent;
}

// ---------------------------------------------------------------------------
// 登录态与健康
// ---------------------------------------------------------------------------

/**
 * `authState` 的返回值。
 *
 * `expired` 是必要的中间态：凭据过期但可自动续期的插件，用布尔表达不出来——
 * 用户会看到「已登录」却拉不到数，或看到「未登录」又被自动恢复。
 */
export type AuthState =
    | { state: 'loggedOut' }
    | { state: 'loggedIn'; accountName?: string }
    | { state: 'expired'; message?: string };

/** `health` 的返回值。 */
export type PluginHealth =
    | { status: 'healthy' }
    | { status: 'degraded'; message?: string }
    | { status: 'broken'; message?: string };

// ---------------------------------------------------------------------------
// 详情页 UI
// ---------------------------------------------------------------------------

/** 语义色调，具体颜色由宿主按主题决定。 */
export type Tone = 'neutral' | 'ok' | 'warn' | 'error';

/** 图表上的一个数据点。 */
export interface ChartPoint {
    label: string;
    value: number;
}

/** 宿主内置图表。优先用它们而不是 `customImage`：一次实现、所有插件受益，且自动跟随深浅色。 */
export type ChartSpec =
    | { kind: 'line'; points: ChartPoint[]; unit?: string }
    | { kind: 'bar'; bars: ChartPoint[]; unit?: string; /** 高亮第几根（如「今天」），-1 表示都不高亮 */ highlightIndex?: number }
    | { kind: 'progressRing'; /** 0..1，越界由宿主收敛 */ fraction: number; centerText: string; subtitle?: string };

/** 间距档位。宿主按设计系统映射到具体 dp，插件不能写像素。 */
export type Spacing = 'none' | 'compact' | 'normal' | 'loose';

/** 文字层级。宿主映射到标题 / 正文 / 说明三档字号。 */
export type Emphasis = 'heading' | 'body' | 'caption';

/**
 * 一个「要么给静态值、要么指向宿主数据源」的字段。
 *
 * 写成 `{ binding: '<source>:<key>:<field>' }` 即把该字段交给宿主托管：
 * 数据变了宿主自己重绘，插件不必每来一条消息就重调 `detailUi`。
 *
 * ## 支持的字段（v5）
 *
 * | 字段 | 数据源 |
 * |---|---|
 * | `chat.messages` | `session:<key>:messages` |
 * | `picker.candidates` | `sessionList::items` |
 * | `list.items` | `session:<key>:messages` |
 * | `text.body` / `statusCard.value` | `session:<key>:status` / `session:<key>:turnState` |
 * | `input.enabled` | `session:<key>:inputEnabled` |
 *
 * ## 写法：一律三段
 *
 * - 有实例概念的源写实例 key：`session:<哪条会话>:<字段>`
 * - **没有实例 key 的源把中间段留空**：`sessionList::items`
 *   （会话清单回答的是「有哪些会话」，不属于任何一条）
 *
 * 两段式（`sessionList:items`）是**格式错误**：`submitTo` 已经占用了两段形态
 * （第二段是实例 key），同一段文本有两种解释会让排查变得很难。
 * 段数不对、或 source / field 为空时，该字段退回默认值，不会让整页打不开。
 */
export type Bound<T> = T | { binding: string };

/** 一条消息。`pending` 表示尚未完成（流式输出中或等待回复）。 */
export interface ChatMessage {
    /** 未知值按 `system` 处理（最弱化的呈现，不会冒充任何一方）。 */
    role: 'user' | 'assistant' | 'system';
    text: string;
    /**
     * 稳定标识，宿主据此去重与定位。留空安全（宿主按下标兜底），
     * 但同一段历史在两次 `detailUi` 里顺序变化时，兜底 id 也会变——
     * 跨刷新要保持同一条消息的视觉稳定就自己给。
     */
    id?: string;
    /**
     * 轮次标识：同一轮问答（用户问 + 对方答 + 系统提示）共用一个值。
     * 宿主用它在轮次之间断开分组，让用户看出「这一组是一问一答」。
     */
    turnId?: string;
    /** 展示用时间文案，为空不显示。 */
    timeLabel?: string | null;
    pending?: boolean;
}

/**
 * 清单选择器的一个候选。
 *
 * ⚠️ `label` 与 `detail` **会直接展示给用户**，不得拼入凭据、token、
 * 内部地址等敏感信息（与 `showMessage` 的约束一致）。
 */
export interface PickerCandidate {
    /** 机器可读标识，**必填**；回传给 `onAction` 的就是它 */
    id: string;
    /** 主文案 */
    label: string;
    /** 次要说明（如最后活跃时间、消息条数），空则不显示 */
    detail?: string;
    /** 语义色调，用于状态标记（如异常会话） */
    tone?: Tone;
    /** 置灰且不可选 */
    disabled?: boolean;
}

/** 选择器的呈现方式。认不出的值按 `modal` 处理。 */
export type PickerMode = 'modal' | 'inline';

/**
 * 详情页的一个节点，用 `type` 区分。
 *
 * 宿主不认识 `type` 时会画一个「此版本暂不支持」的占位，不会整页报错，
 * 也不会静默消失——后者会让用户以为插件坏了。
 *
 * 每个节点都支持 `id`（排序用的稳定标识）与 `locked`（显式禁止拖动）。
 */
export type UiNode =
    // ---- 容器 ----
    | {
          type: 'column';
          children: UiNode[];
          /** 间距档位，默认 normal */
          spacing?: Spacing;
          /** 是否套一层卡片，默认 true。**只有这里能决定套不套卡片**，嵌套容器一般写 false */
          card?: boolean;
          title?: string;
          id?: string;
          locked?: boolean;
      }
    | { type: 'row'; children: UiNode[]; spacing?: Spacing; /** 窄屏自动折行，默认 true */ wrap?: boolean; id?: string; locked?: boolean }
    | { type: 'collapsible'; title: string; children: UiNode[]; /** 初始是否折叠，默认 true */ collapsed?: boolean; /** 折叠时标题右侧的摘要 */ summary?: string; id?: string; locked?: boolean }
    | { type: 'spacer'; size?: Spacing; id?: string; locked?: boolean }
    | { type: 'divider'; id?: string; locked?: boolean }

    // ---- 内容 ----
    | {
          type: 'text';
          title?: string;
          body?: Bound<string>;
          tone?: Tone;
          emphasis?: Emphasis;
          /**
           * 是否套一层卡片，默认 `true`（保持既有观感）。
           *
           * 想要「一段不带边框的说明文字」时置 `false`——嵌在卡片里的说明
           * 文字若再套一层卡片，会出现两层边框，看起来像表格。
           */
          card?: boolean;
          id?: string;
          locked?: boolean;
      }
    | { type: 'statusCard'; label: string; value: Bound<string>; tone?: Tone; detail?: string; id?: string; locked?: boolean }
    | { type: 'chart'; chart: ChartSpec; title?: string; id?: string; locked?: boolean }
    | {
          type: 'image';
          dataUri: string;
          /**
           * 宽高比，默认 1.6。**宿主会按它占位**，避免图片解码完成后整页往下跳。
           * 给 `<= 0` 表示跟随图片自身尺寸。
           */
          aspectRatio?: number;
          contentDescription?: string;
          id?: string;
          locked?: boolean;
      }
    | { type: 'progress'; /** 0..1，越界由宿主收敛 */ fraction: number; label?: string; valueText?: string; tone?: Tone; id?: string; locked?: boolean }
    | {
          type: 'code';
          code: string;
          /**
           * v5 起**已移除** `language`：宿主不做语法高亮，留一个不用的字段
           * 等于契约说谎。需要标注语言时用 `text` 的 `title` 写「示例（Kotlin）」。
           */
          id?: string;
          locked?: boolean;
      }

    // ---- 对话 ----
    | {
          type: 'chat';
          /**
           * 消息列表；绑定到 `session:<key>:messages` 时由宿主持续推送。
           *
           * 与旧做法（`list` + 绑定）的关键区别：角色、轮次、流式标记到这里
           * **不会**被降级成一堆 `text`，宿主统一负责气泡、对齐、合并、
           * 流式光标与自动贴底。
           */
          messages?: Bound<ChatMessage[]>;
          /** 自己（用户）一侧的显示名；空则不显示 */
          selfName?: string;
          /** 对方（Agent / 服务端）一侧的显示名；空则不显示 */
          peerName?: string;
          /** 超过后内部滚动；0（默认）表示跟随内容、由整页滚动承接 */
          maxHeightDp?: number;
          /** 是否显示时间，默认 true */
          showTime?: boolean;
          /** 无消息时的提示文案，默认「暂无对话」 */
          emptyHint?: string;
          id?: string;
          locked?: boolean;
      }

    // ---- 集合 ----
    | {
          type: 'list';
          /**
           * 静态条目时是「每项的节点数组」，或绑定到会话消息源。
           *
           * 两层结构（条目 → 条目内的节点）让宿主能给每项插入分隔线与稳定 key。
           */
          items?: Bound<UiNode[][]>;
          dense?: boolean;
          /** 条目之间的分隔线，默认 true */
          separator?: boolean;
          /** 超过后内部滚动；0（默认）表示跟随内容 */
          maxHeightDp?: number;
          emptyHint?: string;
          /**
           * 非空时**整行可点**：回传 `onAction(itemActionId, itemPayloads[i])`。
           * 行内有按钮时，按钮的点击优先，不会误触整行。
           */
          itemActionId?: string;
          /** 与 `items` 逐项对应的回传值；长度不匹配时按空串处理，不会丢弃整张列表 */
          itemPayloads?: string[];
          id?: string;
          locked?: boolean;
      }
    | { type: 'table'; columns: Array<{ title: string; /** 列宽权重，<=0 表示按内容自适应 */ weight?: number; /** 列对齐，v5 起真正生效 */ align?: 'start' | 'center' | 'end' }>; rows: string[][]; id?: string; locked?: boolean }
    | { type: 'keyValue'; entries: Array<{ key: string; value: string; tone?: Tone }>; id?: string; locked?: boolean }

    // ---- 交互 ----
    | {
          type: 'button';
          label: string;
          /** 为空时渲染成禁用态。 */
          actionId?: string;
          primary?: boolean;
          enabled?: boolean;
          /** 点击时回传给 `onAction` 的固定 value，多个按钮共用一个 actionId 时用它区分 */
          payload?: string;
          id?: string;
          locked?: boolean;
      }
    | {
          type: 'input';
          actionId: string;
          placeholder?: string;
          submitLabel?: string;
          multiline?: boolean;
          enabled?: Bound<boolean>;
          /**
           * 填了就把输入投给数据源（如 `session:main`），**不进** `onAction`。
           * `session:<key>` 里的 key 就是**要发往哪一条会话**。
           */
          submitTo?: string;
          id?: string;
          locked?: boolean;
      }
    | {
          type: 'select';
          actionId: string;
          options: Array<{
              value: string;
              label?: string;
              /** 随提交一并回传的结构化数据（如该项对应的会话 id） */
              payload?: string;
          }>;
          label?: string;
          multiple?: boolean;
          /** 非空时出现确认按钮；单选留空=选中即提交 */
          submitLabel?: string;
          enabled?: boolean;
          /**
           * 已选中的 value 列表。
           *
           * - **非空 = 受控**：界面显示什么完全由插件给的树决定，树重建也不会丢选区
           * - **空（默认）= 非受控**：退回旧语义，选区由宿主内部记忆，
           *   适合「选中即提交、不需要回显」的单选
           *
           * 选项本身是静态列表；「从数据源里挑一项」请用 `picker`。
           */
          selectedValues?: string[];
          id?: string;
          locked?: boolean;
      }
    | { type: 'toggle'; actionId: string; label: string; checked?: boolean; enabled?: boolean; id?: string; locked?: boolean }
    | {
          type: 'picker';
          /** 为空时渲染成禁用态 */
          actionId: string;
          /** 弹层标题；`inline` 模式下作为段落小标题 */
          title?: string;
          /** 静态候选，或绑定到清单源（写法 `{binding:'sessionList::items'}`，中间段留空） */
          candidates?: Bound<PickerCandidate[]>;
          /** 呈现方式，默认 `modal` */
          mode?: PickerMode;
          /** 是否多选；多选回传 JSON 数组文本 */
          multiple?: boolean;
          /**
           * 已选中的候选 id，**由插件回传**：树重建后选中态不丢。
           * 弹层打开期间的选择是临时的，关闭即丢弃，以插件给的为准。
           */
          selectedIds?: string[];
          /** 多选时的确认按钮文案，空则用「确定」 */
          submitLabel?: string;
          emptyHint?: string;
          id?: string;
          locked?: boolean;
      }

    // ---- 媒体（宿主当前降级为「暂不支持」占位，可先写，等宿主实现播放器）----
    | { type: 'audio'; source: string; title?: string; subtitle?: string; durationSeconds?: number; id?: string; locked?: boolean }
    | { type: 'video'; source: string; posterDataUri?: string; aspectRatio?: number; title?: string; autoplay?: boolean; id?: string; locked?: boolean };

// ---------------------------------------------------------------------------
// 按钮动作
// ---------------------------------------------------------------------------

/**
 * `onAction(actionId, value?)` 的约定。
 *
 * - `button`：只有 `actionId`，`value` 是声明的 `payload`（未声明则为空）
 * - `input` 提交：`value` 是用户输入的文本。**声明了 `submitTo` 的输入框不会到这里**
 * - `select`：单选传选中项的 value；多选传 JSON 数组文本，如 `["a","c"]`
 * - `toggle`：传 `"true"` / `"false"`
 * - `list` 整行点击：传该行的 `itemPayloads[i]`（未声明则为空串）
 * - `picker`：单选传候选 `id`；多选传 JSON 数组文本，如 `["s1","s3"]`
 *
 * 只写一个形参（`function onAction(actionId)`）同样可用，宿主会多传一个 `value`。
 */

/**
 * `onAction` 的返回值，决定宿主接下来做什么。
 *
 * 插件**不能**直接操作宿主界面。
 */
export type ActionResult =
    /** 界面内容变了，用新的节点树重绘。最常用：处理完动作顺手带回最新 UI。 */
    | { type: 'refreshUi'; root: UiNode }
    /** 只弹一句提示，界面不变。不得拼入凭据等敏感内容。 */
    | { type: 'showMessage'; text?: string }
    /** 请求宿主立刻跑一次完整采集并落库。 */
    | { type: 'triggerSync' }
    | { type: 'noop' };
