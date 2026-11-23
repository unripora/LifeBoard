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
 */

// ---------------------------------------------------------------------------
// 采集结果
// ---------------------------------------------------------------------------

/** 一条指标值。`metricKey` 必须是 manifest 里声明过的。 */
interface PluginMetric {
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
type FetchResult = PluginMetric[] | FetchResultObject;

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
 */
type FetchResultObject =
    | { status: 'success'; metrics: PluginMetric[] }
    | { status: 'empty'; reason?: string }
    | { status: 'temporary'; message?: string; retryAfterSeconds?: number }
    | { status: 'authRequired'; message?: string }
    | { status: 'failure'; message?: string };

// ---------------------------------------------------------------------------
// 登录
// ---------------------------------------------------------------------------

/** 一次登录交互中要用户填的一个字段。 */
interface InputField {
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
type LoginResult =
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
// 登录态与健康
// ---------------------------------------------------------------------------

/**
 * `authState` 的返回值。
 *
 * `expired` 是必要的中间态：凭据过期但可自动续期的插件，用布尔表达不出来——
 * 用户会看到「已登录」却拉不到数，或看到「未登录」又被自动恢复。
 */
type AuthState =
    | { state: 'loggedOut' }
    | { state: 'loggedIn'; accountName?: string }
    | { state: 'expired'; message?: string };

/** `health` 的返回值。 */
type PluginHealth =
    | { status: 'healthy' }
    | { status: 'degraded'; message?: string }
    | { status: 'broken'; message?: string };

// ---------------------------------------------------------------------------
// 详情页 UI
// ---------------------------------------------------------------------------

/** 语义色调，具体颜色由宿主按主题决定。 */
type Tone = 'neutral' | 'ok' | 'warn' | 'error';

/** 图表上的一个数据点。 */
interface ChartPoint {
    label: string;
    value: number;
}

/** 宿主内置图表。优先用它们而不是 `customImage`：一次实现、所有插件受益，且自动跟随深浅色。 */
type ChartSpec =
    | { kind: 'line'; points: ChartPoint[]; unit?: string }
    | { kind: 'bar'; bars: ChartPoint[]; unit?: string; /** 高亮第几根（如「今天」），-1 表示都不高亮 */ highlightIndex?: number }
    | { kind: 'progressRing'; /** 0..1，越界由宿主收敛 */ fraction: number; centerText: string; subtitle?: string };

/** 单个按钮。 */
interface ButtonSpec {
    /** 显示文案，留空则用 actionId。 */
    label?: string;

    /** 回传给 `onAction` 的标识，插件内唯一且必填。 */
    actionId: string;

    /** 主操作按钮，一行里只应有一个。 */
    primary?: boolean;

    /** 默认 true。 */
    enabled?: boolean;
}

/**
 * 详情页的一个区块，用 `type` 区分。
 *
 * 不认识的 `type` 会被宿主直接跳过（旧宿主遇到新插件的区块时只少显示一块），
 * 不会整页报错。
 */
type UiBlock =
    | { type: 'text'; title: string; body?: string; tone?: Tone }
    | {
          type: 'statusCard';
          label: string;
          value: string;
          tone?: Tone;
          /** 补充说明，如「上次成功：11-17 08:00」。 */
          detail?: string | null;
      }
    | { type: 'buttonRow'; buttons: ButtonSpec[] }
    | { type: 'chart'; title: string; chart: ChartSpec }
    | { type: 'customImage'; /** `data:image/...;base64,...` */ dataUri: string; /** 宽高比，默认 1.6 */ aspectRatio?: number }
    | { type: 'messageList'; /** 按时间正序，最后一条在底部 */ messages: ChatMessage[] }
    | {
          type: 'inputBox';
          /** 提交时回传给 `onAction` 的标识。 */
          actionId: string;
          placeholder?: string;
          /** 提交按钮文案，默认「发送」。 */
          submitLabel?: string;
          /** 默认 true。 */
          multiline?: boolean;
          /** 默认 true；连接断开等场合置 false，只展示不可输入。 */
          enabled?: boolean;
      }
    | {
          type: 'approval';
          title: string;
          detail?: string;
          /** 与按钮同构；点击后回传该项的 actionId。 */
          options: ButtonSpec[];
          /** 等待态：只显示「等待处理…」，不显示选项，防止重复提交。 */
          waiting?: boolean;
      };

/** 一条消息。`pending` 表示尚未完成（流式输出中或等待回复）。 */
interface ChatMessage {
    /** 未知值按 assistant 处理。 */
    role: 'user' | 'assistant' | 'system';
    text: string;
    /** 展示用时间文案，为空不显示。 */
    timeLabel?: string | null;
    pending?: boolean;
}

// ---------------------------------------------------------------------------
// 按钮动作
// ---------------------------------------------------------------------------

/**
 * `onAction(actionId, value?)` 的约定。
 *
 * - `buttonRow` / `approval` 的选项：只有 `actionId`，`value` 为 `undefined`
 * - `inputBox` 提交：`actionId` 是输入框的标识，`value` 是用户输入的文本
 *
 * 只写一个形参（`function onAction(actionId)`）同样可用，宿主会多传一个 `value`。
 */

/**
 * `onAction` 的返回值，决定宿主接下来做什么。
 *
 * 插件**不能**直接操作宿主界面。
 */
type ActionResult =
    /** 界面内容变了，用新区块重绘。最常用：处理完动作顺手带回最新 UI。 */
    | { type: 'refreshUi'; blocks: UiBlock[] }
    /** 只弹一句提示，界面不变。不得拼入凭据等敏感内容。 */
    | { type: 'showMessage'; text?: string }
    /** 请求宿主立刻跑一次完整采集并落库。 */
    | { type: 'triggerSync' }
    | { type: 'noop' };
