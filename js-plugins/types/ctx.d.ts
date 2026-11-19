/**
 * LifeBoard JS 插件宿主上下文 `ctx` 的类型声明。
 *
 * ## 这份文件从哪来
 *
 * 逐字段对照宿主实现写出，**不是照抄文档**：
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsPluginContextBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsHttpBridge.kt`
 * - `shared/src/jvmMain/kotlin/com/lifeboard/shared/plugin/js/JsCryptoBridge.kt`
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
     * @param domain 必填，不能为空
     * @param path 默认 `/`
     */
    cookieSet(session: string, name: string, value: string, domain: string, path?: string): void;

    /** 删除同名 cookie。 */
    cookieRemove(session: string, name: string): void;

    /** 清空会话全部 cookie。 */
    cookieClear(session: string): void;

    /** 导出会话全部 cookie 为 `{ name: value }`。 */
    cookieAll(session: string): Record<string, string>;
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
}

interface CodecApi {
    base64Encode(bytes: Bytes): string;
    /** 返回字节数组，不是字符串。 */
    base64Decode(text: string): Bytes;
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

    put(slot: string, value: string): void;

    remove(slot: string): void;

    /** 清空本插件全部凭据。 */
    clear(): void;
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
 * 宿主注入给插件的上下文。
 *
 * 插件源码里直接引用裸 `ctx`（不 import），由宿主在每次调用插件方法时注入。
 */
interface PluginCtx {
    credentials: CredentialsApi;
    files: FilesApi;
    http: HttpApi;
    crypto: CryptoApi;
    codec: CodecApi;
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

    /** 本插件 id。 */
    pluginId(): string;
}
