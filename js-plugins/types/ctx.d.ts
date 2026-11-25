/**
 * LifeBoard JS 插件宿主上下文 `ctx` 的类型声明。
 *
 * ## 这份文件从哪来
 *
 * 逐字段对照宿主实现写出，**不是照抄文档**：
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsPluginContextBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsHttpBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsCryptoBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsWebSocketBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsStreamBridge.kt`
 *
 * 宿主新增函数时这份声明要同步跟着改，否则 TS 会报「属性不存在」而宿主明明支持。
 *
 * ## 最重要的约束：只声明 QuickJS 真实存在的能力
 *
 * 插件跑在 QuickJS 里，不是 Node，也不是浏览器。下面这些在本地 Node 调试时能用，
 * 在真机上**直接抛 `xxx is not defined`**：
 *
 * | 不能用 | 替代 |
 * |---|---|
 * | `new URL()` / `URLSearchParams` | 自己拆 host / path |
 * | `TextEncoder` / `TextDecoder` | 手写 UTF-8 编码 |
 * | `Buffer` | `number[]`（字节数组） |
 * | `atob` / `btoa` | `ctx.codec.base64Encode/base64Decode` |
 * | `crypto.getRandomValues` | `Math.random()` |
 * | `fetch` / `require` / `process` / `fs` | `ctx.http` / 包内相对 import |
 *
 * 所以 `tsconfig.json` 里**不要**引入 `"lib": ["DOM", "Node"]`。
 * 让 TS 在这些全局对象上报错，正是这份声明的主要价值——它在编译期就挡住
 * 「本地能跑、手机崩」这类问题。
 */

/** 字节数组。QuickJS 没有 Buffer / Uint8Array 的完整支持，统一用普通数组。 */
type Bytes = number[];

/** 键为小写、值为字符串的响应头表。 */
type HeaderMap = Record<string, string>;

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

/** 单次请求的可选项。 */
interface HttpOptions {
    /** 额外请求头，会覆盖会话默认头。 */
    headers?: HeaderMap;

    /** 本次请求的 User-Agent，覆盖会话级设置。 */
    userAgent?: string;

    /** body 的 Content-Type，不传按 `application/json; charset=utf-8`。 */
    contentType?: string;

    /**
     * 是否跟随 3xx 重定向。不传则读会话设置（默认 true）。
     *
     * 语义是「设为 false 就只发一次」，把 3xx 原样返回给调用方，
     * 不是「最多跳一次」。逐跳拿中间 Set-Cookie 时靠它。
     */
    followRedirects?: boolean;

    /**
     * 本次请求超时（毫秒），会被收敛到 1000～180000。
     * 不传用默认（连接 15s，读写 30s）。
     */
    timeoutMs?: number;

    /**
     * 是否允许宿主在网络层重试。
     *
     * 默认只对 GET / HEAD / OPTIONS 开启。POST 想重试必须显式传 true，
     * 因为重复提交可能让服务端执行两次（重复发短信、重复下单）。
     */
    retry?: boolean;
}

/**
 * 一次 HTTP 响应。
 *
 * **4xx / 5xx 不会 reject**，正常返回并由调用方读 `status` 判断。
 * 只有网络层失败（超时、DNS、证书）才抛异常。
 */
interface HttpResponse {
    /** HTTP 状态码（数字，可直接与 400 比较）。 */
    status: number;

    /** 是否 2xx（布尔，可直接 `if (!res.ok)`）。 */
    ok: boolean;

    /** 文本响应体；二进制响应时为空串。 */
    body: string;

    /**
     * 二进制响应体的 base64；文本响应时为 null。
     *
     * 图片这类响应 `body` 是空串，内容只在这里。
     */
    bodyBase64: string | null;

    /**
     * 宿主用**严格** `JSON.parse` 解析出的对象，解析失败为 null。
     *
     * 注意：响应带前缀（如 `&&&START&&&`）时这里**恒为 null**，
     * 必须自己从 `body` 解析。
     */
    json: unknown | null;

    /** 响应头，键已转小写。 */
    headers: HeaderMap;

    /** 原始 Set-Cookie，一个响应可能下发多条。 */
    setCookie: string[];
}

/** `ctx.http.lastError()` 的稳定分类。 */
type HttpErrorKind = 'timeout' | 'connect' | 'dns' | 'tls' | 'other';

/** 表单参数：对象，或 `[[key, value]]` 二元组数组（用于同名参数）。 */
type FormParams = Record<string, string | number | boolean | null> | Array<[string, string | number | boolean | null]>;

interface HttpApi {
    /** GET 请求。`session` 缺省落到当前默认会话。 */
    get(url: string, options?: HttpOptions, session?: string): Promise<HttpResponse>;

    /** POST 请求。body 传 `''` 或 `null` 都会补成合法的空 body。 */
    post(url: string, body?: string | null, options?: HttpOptions, session?: string): Promise<HttpResponse>;

    /** 任意方法。 */
    request(method: string, url: string, body?: string | null, options?: HttpOptions, session?: string): Promise<HttpResponse>;

    /**
     * 纯表单编码，不发请求。支持对象与二元组数组。
     *
     * @throws 入参既不是对象也不是数组时直接报错，不会静默返回空表单。
     */
    form(params: FormParams): string;

    /**
     * 表单 POST，自动带 `application/x-www-form-urlencoded`。
     *
     * @throws 入参类型错误时直接报错。
     */
    postForm(url: string, params: FormParams, options?: HttpOptions, session?: string): Promise<HttpResponse>;

    /**
     * 上一次失败的稳定分类，没有失败时为 null。
     *
     * 这是瞬时状态：并发请求会互相覆盖，需要精确归属时请在同一个 catch 里立刻读。
     * 不要用异常 message 做字符串匹配——OkHttp 的措辞会随版本变。
     */
    lastError(): HttpErrorKind | null;

    /**
     * 建一个会话（或在已存在时取回它），返回会话名。
     *
     * cookie 由宿主按会话自动管理，插件不要手写 `Cookie` 头。
     * `adoptAsDefault` 默认 true：后建的会话成为默认，后续不带会话名的请求走它。
     *
     * @param name 会话名，只在**本插件内**有效，不同插件互不可见
     */
    session(name?: string, adoptAsDefault?: boolean): string;

    /** 设置会话默认头；`value` 传 null 表示删除该头。 */
    setHeader(session: string, name: string, value: string | null): void;

    /** 设置会话默认 User-Agent。 */
    setUserAgent(session: string, userAgent: string | null): void;

    /** 设置会话级是否跟随重定向。 */
    setFollowRedirects(session: string, follow: boolean): void;

    /** 读 cookie，取不到返回 null。同名 cookie 可用 domain 区分。 */
    cookieGet(session: string, name: string, domain?: string): string | null;

    /**
     * 种一个 cookie。
     *
     * 主要用在**伪造客户端身份**：服务端要求先看到某个它没下发过的 cookie。
     *
     * @param domain 必填，**只能是主机名，不能带端口**（`example.com`，不是
     *   `example.com:8443`）。带端口时 cookie 会被拒绝、静默种不进去，
     *   随后每个请求都拿不到它。注意与 `Host` 头区分：后者要带端口。
     * @param path 默认 `/`
     */
    cookieSet(session: string, name: string, value: string, domain: string, path?: string): void;

    /** 删除同名 cookie。 */
    cookieRemove(session: string, name: string): void;

    /** 清空会话全部 cookie。 */
    cookieClear(session: string): void;

    /** 导出会话全部 cookie 为 `{ name: value }`。 */
    cookieAll(session: string): Record<string, string>;

    /**
     * 取回一个自签服务的 CA 证书（DER 的 base64）。
     *
     * 这一步**不校验**服务端 TLS——此刻 CA 还没被信任，正常校验必然失败。
     * 安全性由随后的 {@link HttpApi.trustCa trustCa} 指纹比对保证：
     * 中间人替换的证书过不了那一步。
     *
     * @param url 必须是 `https://`；http 拿回一张「CA」毫无意义
     */
    caCertificate(url: string): Promise<string>;

    /**
     * 信任一张自签 CA。
     *
     * 只在指纹与期望值一致时装上，否则抛错——插件确实可以声明信任目标，
     * 但只能在 manifest 允许的范围内：写死指纹时只认那一张；写 `runtime`
     * 时必须由 `expectedSha256` 明确给出。装好之后，本插件的 `ctx.http.*`
     * 与 `ctx.ws.*` 都只信任这张 CA。必须在第一次业务请求之前调用。
     *
     * @param caBase64 `caCertificate` 返回的 base64，或包内自带的证书
     * @param expectedSha256 manifest 为 `runtime` 时必填：期望的 CA 指纹
     *   （64 位十六进制，通常取自配对链接的 `instance=` 段）
     */
    trustCa(caBase64: string, expectedSha256?: string): void;
}

// ---------------------------------------------------------------------------
// WebSocket
// ---------------------------------------------------------------------------

/** 建立连接的可选项。 */
interface WsOptions {
    /** 握手请求额外请求头。 */
    headers?: HeaderMap;

    /**
     * 复用 HTTP 会话的 cookie。
     *
     * WS 与 HTTP 是两套客户端，`CookieJar` 挂在 HTTP 会话上，握手不会自动带上。
     * 给会话名后，宿主取该会话里对握手 URL 生效的 cookie 并写成 `Cookie` 头；
     * 插件自己写了 `Cookie` 头时以插件为准。
     *
     * 网关这类「只读 Cookie 鉴权」的服务必须给，否则握手会被当成匿名请求拒绝。
     */
    session?: string;
}

/** `ctx.ws.state()` 的返回值。 */
interface WsState {
    /** `closed` 之后 `recv` 不再返回帧，直接抛错。 */
    state: 'connecting' | 'open' | 'closing' | 'closed';

    /** 已到达、但插件还没取走的帧数。 */
    queued: number;

    /** 因插件消费过慢被丢弃的帧数。上界为每条连接 512 帧，超出丢最旧。 */
    dropped: number;
}

/** 文本帧。 */
interface WsTextFrame {
    type: 'text';
    data: string;
}

/** 二进制帧。 */
interface WsBinaryFrame {
    type: 'binary';
    bytes: Bytes;
}

/**
 * 终态帧，`recv` 拿到它之后连接不再产出任何帧。
 *
 * 写法上仍是一条「帧」而不是 reject：对端正常关闭是业务事件，
 * 不应该以异常形式进入插件的 catch，否则控制流会被打乱。
 */
interface WsCloseFrame {
    type: 'close';
    code: number;
    reason: string;
    /** 对端正常关闭为 true；传输层失败（断网、证书、DNS）为 false。 */
    clean: boolean;
    /** 传输层失败原因；正常关闭为 null。 */
    error: string | null;
}

type WsFrame = WsTextFrame | WsBinaryFrame | WsCloseFrame;

/**
 * 宿主提供的 WebSocket 长连接。
 *
 * **没有「注册回调」这种用法**：宿主函数只能收发 JSON，JS 函数传不进来。
 * 改用拉取模型，插件自己循环消费：
 *
 * ```js
 * const id = await ctx.ws.connect(url);
 * for (;;) {
 *     const frame = await ctx.ws.recv(id);
 *     if (frame.type === 'close') break;
 *     handle(frame);
 * }
 * ```
 */
interface WsApi {
    /** 建连。握手成功后 resolve 为连接 id；失败时 reject。 */
    connect(url: string, options?: WsOptions): Promise<string>;

    /**
     * 取下一条帧；当前没有帧时挂起，直到帧到达或连接终结。
     *
     * 同一条连接**同一时刻只允许一个** `recv` 在等待，第二个会同步抛错。
     *
     * @throws 连接不存在、或已关闭且帧已取完
     */
    recv(id: string): Promise<WsFrame>;

    /**
     * 发一条文本帧。
     *
     * @throws 连接不在 `open` 状态时同步抛错
     */
    send(id: string, text: string): void;

    /**
     * 发一条二进制帧。字节每个应在 0~255。
     *
     * @throws 连接不在 `open` 状态、或字节越界时同步抛错
     */
    sendBinary(id: string, bytes: Bytes): void;

    /**
     * 主动关闭。
     *
     * `code` 只允许 `1000` 或 `3000~4999`，`reason` 不超过 123 个 UTF-8 字节——
     * 这是 WebSocket 协议自身的限制，越界宿主会同步抛错而不是发出去再说。
     */
    close(id: string, code?: number, reason?: string): void;

    /** 读连接状态与积压计数，用于自检与日志。 */
    state(id: string): WsState;
}

// ---------------------------------------------------------------------------
// 流式输出
// ---------------------------------------------------------------------------

/**
 * 把生成中的片段推给界面。**同步返回，不等待界面**。
 *
 * 与 HTTP / WS 的根本区别：那两个是「插件问宿主」，这个是「插件告诉宿主」。
 * 宿主不做确认、不做排队、也不回传任何结果，所以调用后立刻继续跑自己的逻辑。
 *
 * 三条使用约定：
 * 1. 只在 `onAction` 执行期间推送——别的时机推出去没有任何人接收，内容会被丢掉；
 * 2. 推的是**新增片段**，不是完整回复。宿主负责拼接，插件不要自己累积后整段重发；
 * 3. 最终结论必须通过 `onAction` 的返回值（`refreshUi` 的区块）给出。
 *    增量只是给用户「在动」的感觉，刷新后的区块才是权威结果——两者不一致时以区块为准。
 *
 * ```js
 * for (const chunk of chunks) {
 *     ctx.stream.emit(chunk);
 * }
 * // 完整消息列表由插件自己组装后作为最终结果返回
 * return { type: 'refreshUi', blocks: blocks };
 * ```
 */
interface StreamApi {
    /**
     * 推一段增量文本。
     *
     * @param text 片段内容。空串会被忽略；单段过大时宿主按缓冲区上限自然丢弃最旧的片段，
     *             所以不要把整篇回复一次性当一段推。
     */
    emit(text: string): void;
}

// ---------------------------------------------------------------------------
// 其它能力
// ---------------------------------------------------------------------------

/** 字符串 → 字节数组。插件需自己实现，`ctx.crypto.*` 只收字节。 */
interface CryptoApi {
    md5Hex(bytes: Bytes): string;
    sha1Base64(bytes: Bytes): string;
    sha256Base64(bytes: Bytes): string;
    sha256Hex(bytes: Bytes): string;

    /** SHA-256，字节进、字节出（32 字节）。需要原始摘要时用这个，不是 hex。 */
    sha256Bytes(bytes: Bytes): Bytes;

    /**
     * 安全随机字节，等价于 `crypto.getRandomValues(new Uint8Array(n))`。
     *
     * @param length 1~4096，越界宿主抛错
     */
    randomBytes(length: number): Bytes;
}

interface CodecApi {
    /** 标准 Base64（带 `=` 补齐）。 */
    base64Encode(bytes: Bytes): string;
    /** 标准 Base64 解码，返回字节数组，不是字符串。 */
    base64Decode(text: string): Bytes;

    /** URL-safe Base64，**不带 `=` 补齐**，与上游 `toBase64Url` 输出一致。 */
    base64UrlEncode(bytes: Bytes): string;
    /** URL-safe Base64 解码；带不带 `=` 都能解。非法输入返回空数组。 */
    base64UrlDecode(text: string): Bytes;

    /**
     * UTF-8 编码。QuickJS 没有 `TextEncoder`，用这个替代。
     *
     * @param text 允许空串（结果是零字节）
     */
    utf8Encode(text: string): Bytes;

    /**
     * UTF-8 解码。QuickJS 没有 `TextDecoder`，用这个替代。
     *
     * 与 `TextDecoder` 默认行为一致：非法字节序列解成替换符 `\uFFFD`，不抛错。
     */
    utf8Decode(bytes: Bytes): string;
}

/**
 * 插件私有文件目录，卸载时清空。
 *
 * 只能读写相对路径，`..` 会被宿主的规范化检查拦掉。
 */
interface FilesApi {
    /** 读文本；不存在返回 null。 */
    read(path: string): string | null;

    /** 写文本（覆盖）。 */
    write(path: string, content: string): void;

    /**
     * 读二进制，返回 base64；不存在返回 null。
     *
     * 别用 `read` 读二进制：它走 UTF-8 解码，图片 / protobuf 会**不可逆**损坏。
     */
    readBase64(path: string): string | null;

    /** 写二进制（内容为 base64）。base64 非法时宿主报错，不会写空文件。 */
    writeBase64(path: string, base64: string): void;

    /** 列出私有目录下的文件名。 */
    list(): string[];
}

/**
 * 凭据槽位存取。**只有 manifest 的 `credentialSpec` 声明过的槽位能用。**
 */
interface CredentialsApi {
    /** 取不到返回 null。 */
    get(slot: string): string | null;

    /** 写入（覆盖）。 */
    set(slot: string, value: string | null): void;

    put(slot: string, value: string): void;

    remove(slot: string): void;

    /** 清空本插件全部凭据。 */
    clear(): void;
}

/**
 * 会话推送通道：插件 → 宿主。
 *
 * 只在会话期间有效。事件是**受控 JSON**，不是任意 UI——宿主只认下面几种，
 * 未知 `type` 直接报错（便于开发期发现拼写错误），不会静默丢弃。
 */
interface SessionApi {
    /**
     * 推一条会话事件。
     *
     * | type | 字段 | 作用 |
     * |---|---|---|
     * | `message` | `role`、`text` | 追加一条完整消息 |
     * | `delta` | `text` | 追加到当前回复末尾（增量，不是累计） |
     * | `status` | `text`、`inputEnabled` | 状态文案与输入可用性 |
     * | `clear` | 无 | 清空消息 |
     * | `closed` | `reason` | 插件侧关闭会话 |
     */
    emit(payload: string): void;
}

/**
 * 日志。**这是唯一的日志出口**，`console.log` 进不了宿主的日志页。
 *
 * 四条硬约定：
 * 1. 日志失败不能影响业务（包 try/catch）；
 * 2. 不写敏感值（密码、令牌、完整账号）；
 * 3. URL 只记路径（签名常在 query 上）；
 * 4. 关键分支都要留一行。
 */
interface LogApi {
    d(tag: string, message: string): void;
    i(tag: string, message: string): void;
    w(tag: string, message: string): void;
    e(tag: string, message: string): void;
}

/** 已落库的历史指标样本。 */
interface MetricSample {
    /** `YYYY-MM-DD` */
    date: string;
    /**
     * 数值。
     *
     * 宿主桥接按字符串下发（`s.value.toString()`），TS 在两侧都可能出现，
     * 用之前请 `Number(...)` 转一次。
     */
    value: string | number;
}

/** 上报给宿主的原始条目。 */
interface RawRecord {
    timestamp: number;
    value: number;
    detail?: string;
}

/**
 * 插件上下文。
 *
 * 插件源码里直接引用裸 `ctx`（不 import），由宿主在每次调用插件方法时注入。
 *
 * `session` 只在会话期间可用：宿主在调用 `sessionStart` 之前就挂上
 * `ctx.session.emit`，`sessionStop` 之后移除。
 */
interface PluginCtx {
    credentials: CredentialsApi;
    files: FilesApi;
    session: SessionApi;
    http: HttpApi;
    crypto: CryptoApi;
    codec: CodecApi;
    ws: WsApi;
    stream: StreamApi;
    log: LogApi;

    /**
     * 读本插件已落库的指标，不发网络请求。
     *
     * @param metricKey manifest 里声明的 metricKey
     * @param days 取最近多少天，默认 7
     */
    recentMetrics(metricKey: string, days?: number): MetricSample[];

    /**
     * 上报原始条目。
     *
     * @param date 缺省用本次采集日；按日回补历史时显式传
     */
    reportRawRecords(metricKey: string, records: RawRecord[], date?: string): void;

    /**
     * 标记「这个指标查过、但这天确实没数据」。
     *
     * **必须在请求成功后调用**。请求失败时调用它，会把「网络断了」
     * 显示成「当天没有数据」，用户会去怀疑设备而不是网络。
     *
     * @param date 缺省用本次采集日
     */
    reportMetricQueried(metricKey: string, date?: string): void;

    /**
     * 给某个指标**加上**一个值（累加，不是覆盖）。
     *
     * ## 和 `fetch` 的分工
     *
     * `fetch` 是宿主定时拉取的「每日聚合量」；这个函数是插件在任意时刻上报的
     * 「刚刚发生了一件事」。会话完成一轮、任务失败一次，都走这条。
     *
     * 同一天同一指标多次调用会累加——这正是事件计数想要的。
     * 需要覆盖语义请返回 `fetch` 的结果，不要用 `report` 硬凑。
     *
     * `metricKey` 必须属于 manifest 的 `metrics`；未声明的键会被丢弃并记一条
     * 宿主告警（写进去就是一行没有量纲、没人能解释的数据）。
     *
     * **不保证幂等**：只在自己确知「这件事发生了一次」时调用，
     * 不要在重试循环里调用。
     *
     * @param value 本次增量，通常是 1
     * @param at 归属日期 `yyyy-MM-dd`；缺省为宿主本地时区的今天
     */
    report(metricKey: string, value: number, at?: string): void;

    /** 本插件 id。 */
    pluginId(): string;
}
