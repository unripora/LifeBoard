// dsh-mobile 局域网网关接入：自签 CA 钉扎、设备配对、会话 Cookie、CSRF、续期。
//
// 依据：`saya-ch/dsh-mobile` 源码（`external/dsh-mobile`）
//   `src/gateway.ts` 路由与 `authorize()`、`src/http-security.ts` Cookie 与 CSRF、
//   `src/browser-auth-cookies.ts` 前缀规则、`src/network.ts` Origin 策略。
//
// ## 与旧方案的根本差异
//
// 旧方案（`ds-harness-remote`）强依赖公网 Server 中转，且自带 Noise E2EE；
// dsh-mobile 是**局域网直连**：电脑上跑一个网关，手机/桌面直接连它，
// 安全性由「私有 CA + 设备配对 + 短会话」保证，没有额外的应用层加密。
//
// ## 认证的四件事
//
// 1. **CA 钉扎**：网关证书由电脑自建的 CA 签发，必须先取回并 `trustCa`，
//    否则后续每个请求都会 SSL 失败。指纹写在 manifest 的 `pinnedCaSha256`。
// 2. **设备配对**：用户从电脑端拿到 43 位一次性配对码，POST 换 `deviceToken`
//    （长期，只用于续期）与 `sessionToken`（短期，一切业务请求的凭据）。
// 3. **Cookie**：网关的 `authorize()` **只读 Cookie**，全仓没有 Bearer 分支。
//    所以必须把 `sessionToken` 种成 `__Host-dsh_ma_session`，不能塞 Authorization。
// 4. **CSRF + Origin**：写请求要带 `x-dsh-mobile-csrf`；所有请求（尤其 WS）
//    的 `Origin` 必须精确等于网关源，否则 403。
//
// ## 为什么这么多注释
//
// 这四条里任何一条错了，表现都是「403」或「SSL 失败」，看不出是哪一个环节。
// 把每条规则和它的来源写清楚，出问题时能直接对着源码核对，不用重新考古。

/// <reference path="../types/index.d.ts" />

/**
 * 配对链接的解析结果。
 *
 * 电脑端给的链接形如：
 * `<origin>/mobile-access/pair#instance=<CA指纹>&token=<43位配对码>`
 *
 * `instance` 是网关把自己设为「实例 ID」的 CA 指纹（`managed-setup.ts:641`：
 * `instanceId = ca.fingerprint256`），因此**它就是钉扎要用的指纹**。
 */
export interface PairingLink {
    /** 网关地址；粘贴内容里没有 origin 时为空串 */
    gateway: string;
    /** CA 指纹（64 位小写十六进制） */
    instance: string;
    /** 43 位一次性配对码 */
    token: string;
}

/**
 * 解析用户粘贴的配对链接。
 *
 * 为什么必须粘**整条**链接而不是只粘 43 位：指纹只在链接的 `instance=` 里，
 * 裸码没有它就没法钉扎（这套包是分发给多个用户的，指纹不可能写死在包里）。
 *
 * 兼容 `#` 片段与 `?` 查询两种写法；两处都有时以 `#` 为准。
 */
export function parsePairingLink(raw: string): PairingLink | null {
    const text = (raw ?? '').trim();
    if (text.length === 0) return null;
    const queryIndex = text.indexOf('?');
    const hashIndex = text.indexOf('#');
    // 取 `#` 之后（若有），否则 `?` 之后
    let params = '';
    if (hashIndex >= 0) params = text.slice(hashIndex + 1);
    else if (queryIndex >= 0) params = text.slice(queryIndex + 1);
    if (params.length === 0) return null;

    let instance = '';
    let token = '';
    for (const pair of params.split('&')) {
        const eq = pair.indexOf('=');
        if (eq <= 0) continue;
        const key = pair.slice(0, eq);
        const value = pair.slice(eq + 1);
        if (key === 'instance') instance = value.trim().toLowerCase();
        else if (key === 'token') token = value.trim();
    }
    if (instance.length !== 64 || token.length === 0) return null;

    const origin = /^(https?:\/\/[^/?#\s]+)/i.exec(text);
    return {
        gateway: origin === null ? '' : origin[1],
        instance,
        token,
    };
}

/** 取回 CA 证书的固定路径（无需鉴权，见 `gateway.ts:2266-2277`） */
const CA_PATH = '/mobile-access/ca.cer';

/** 三个 auth 接口的公共前缀 */
const AUTH_PREFIX = '/mobile-access/auth';

/** Cookie 名。TLS 下网关优先读 `__Host-` 前缀版（`gateway.ts:1898`） */
const COOKIE_SESSION = '__Host-dsh_ma_session';
const COOKIE_DEVICE = '__Host-dsh_ma_device_id';

/** 写请求的 CSRF 头名（`http-security.ts:8`） */
export const CSRF_HEADER = 'x-dsh-mobile-csrf';

/**
 * 网关 HTTP 会话名。
 *
 * 抽成常量是因为它被两处共用：HTTP 请求（cookie/CSRF 都挂在这个会话上）
 * 和 WebSocket 握手（要按同一个会话取 cookie）。两处各写一个字面量，
 * 一旦有一边改名，表现是「HTTP 正常、WS 403」这种极难联想的故障。
 */
export const GATEWAY_SESSION = 'gateway';

export class GatewayError extends Error {
    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
    ) {
        super(message);
    }
}

// ---------------------------------------------------------------------------
// 地址规范化
// ---------------------------------------------------------------------------

/**
 * 规范化网关地址。
 *
 * dsh-mobile 的网关地址就是 `https://<局域网 IP>:<port>`，不带路径。
 * 手写解析而不用 `new URL()`：QuickJS 没有这个构造函数。
 *
 * 必须是 https：网关在 TLS 模式下才发 `__Host-` 前缀 cookie，
 * 且 http 下的「CA 钉扎」毫无意义。
 */
export function normalizeGatewayUrl(input: string): string {
    const raw = (input ?? '').trim();
    if (raw.length === 0) throw new GatewayError(0, 'GATEWAY_EMPTY', '网关地址不能为空');

    // scheme 必须大小写不敏感：用户从浏览器地址栏复制时常带上大写，
    // 而「HTTPS://... 不合法」这种报错会让人以为自己写错了协议
    const match = /^(https?):\/\/([^/?#]+)([/?#].*)?$/i.exec(raw);
    if (match === null) throw new GatewayError(0, 'GATEWAY_INVALID', `网关地址不合法：${raw}`);
    if (match[1].toLowerCase() !== 'https') {
        throw new GatewayError(0, 'GATEWAY_INSECURE', '网关必须使用 HTTPS');
    }
    const authority = match[2];
    if (authority.includes('@')) {
        throw new GatewayError(0, 'GATEWAY_INVALID', '网关地址不能包含用户名或密码');
    }
    if (!/^[A-Za-z0-9._-]+(:\d{1,5})?$/.test(authority) && !/^\[[0-9A-Fa-f:.]+\](:\d{1,5})?$/.test(authority)) {
        throw new GatewayError(0, 'GATEWAY_INVALID', `网关主机名不合法：${authority}`);
    }
    const rest = match[3];
    if (rest !== undefined && rest !== '/' && rest.length > 0) {
        throw new GatewayError(0, 'GATEWAY_INVALID', '网关地址只能是源，不要带路径');
    }
    return `https://${authority}`;
}

/** 从网关源拆出 host[:port]，用于 cookie 的 domain 与 Host 头 */
export function hostOf(gateway: string): string {
    const match = /^https:\/\/([^/]+)$/.exec(gateway);
    if (match === null) throw new GatewayError(0, 'GATEWAY_INVALID', `网关地址未规范化：${gateway}`);
    return match[1];
}

/**
 * cookie 用的主机名：**不含端口**。
 *
 * `Host` 头与 cookie 的 domain 规则不同，不能混用：
 * - `Host` 头本来就要端口，用 [hostOf]（`192.168.124.71:3443`）
 * - cookie 的 domain 按 RFC 6265 只能是主机名，带端口 OkHttp 直接拒绝
 *   （`unexpected domain`），cookie 静默种不进去，之后每个请求都 401，
 *   表现成「配对成功，一进详情页却说会话已失效」
 */
export function cookieHostOf(gateway: string): string {
    return hostOf(gateway).replace(/:\d+$/, '');
}

/**
 * 派生 WebSocket 地址。
 *
 * 白名单只有四条（`http-security.ts:11-18`），`/api/remote.mux` 是会话主通道。
 */
export function deriveMuxUrl(gateway: string): string {
    return `wss://${hostOf(gateway)}/api/remote.mux`;
}

// ---------------------------------------------------------------------------
// 底层请求
// ---------------------------------------------------------------------------

/** 一次网关请求的可选项 */
interface GatewayRequest {
    method: 'GET' | 'POST';
    path: string;
    body?: unknown;
    /** 会话名；鉴权后的业务请求都要带 */
    session?: string;
    /** 写请求的 CSRF token */
    csrf?: string;
    timeoutMs?: number;
}

/**
 * 发一次网关请求。
 *
 * **Origin 与 Host 都要显式写**：网关的 `assertExternalTrust` 校验它们
 * （`http-security.ts:207-223`）。OkHttp 默认不会带 Origin，
 * 而 Host 在直连 IP 时虽然是自动的，但显式写能与 Origin 保持一致。
 *
 * 4xx/5xx 不 reject（宿主约定），这里自己翻成 [GatewayError]，
 * 并把 `code` 带出来——`authentication_failed` 与 `insufficient_scope`
 * 对应完全不同的处理（重配对 / 续期），只看状态码分不出。
 */
async function gatewayFetch<T>(gateway: string, req: GatewayRequest): Promise<T> {
    const host = hostOf(gateway);
    const headers: Record<string, string> = {
        accept: 'application/json',
        // 必须精确等于网关源，且小写（`network.ts:224-228`）
        origin: gateway,
        host,
    };
    if (req.csrf !== undefined && req.method !== 'GET') {
        headers[CSRF_HEADER] = req.csrf;
    }

    const response = await ctx.http.request(
        req.method,
        `${gateway}${req.path}`,
        req.body === undefined ? null : JSON.stringify(req.body),
        {
            headers,
            contentType: 'application/json; charset=utf-8',
            timeoutMs: req.timeoutMs ?? 15000,
            // 配对会一次性消费配对码、续期会轮换 sessionToken，重发都不安全
            retry: false,
        },
        req.session,
    );

    const payload = response.json as Record<string, unknown> | null;
    if (response.status >= 200 && response.status < 300) {
        if (payload === null || typeof payload !== 'object') {
            throw new GatewayError(response.status, 'BAD_RESPONSE', '网关返回的不是 JSON 对象');
        }
        return payload as T;
    }

    const code = typeof payload?.error === 'string'
        ? payload.error
        : (typeof payload?.code === 'string' ? payload.code : `HTTP_${response.status}`);
    const message = typeof payload?.message === 'string' && payload.message.length > 0
        ? payload.message
        : describeStatus(response.status, code);
    throw new GatewayError(response.status, code, message);
}

/**
 * 把网关的错误码翻成用户能行动的句子。
 *
 * 契约是「message 要能指导下一步动作」：`403` 本身什么也没说，
 * 「配对码无效或已过期，请在电脑端重新生成」才是可执行的。
 */
function describeStatus(status: number, code: string): string {
    if (code === 'source_network_untrusted') {
        return '当前网络不在电脑允许的范围内，请确认手机和电脑在同一个局域网';
    }
    if (code === 'authentication_failed') {
        return '配对码无效或已过期，请在电脑端重新生成配对码';
    }
    if (code === 'rate_limited') {
        return '尝试过于频繁，请稍候再试';
    }
    if (code === 'invalid_request') {
        return '请求参数被网关拒绝，请检查配对码是否为 43 位';
    }
    if (status === 401) return '登录状态已失效，请重新配对';
    if (status === 403) return '网关拒绝了本次请求（域名或来源校验未通过）';
    if (status === 404) return '网关没有这个接口，请确认电脑端的 dsh-mobile 已更新';
    if (status === 415) return '请求格式不被接受（需要 application/json）';
    return `请求失败（HTTP ${status}）`;
}

// ---------------------------------------------------------------------------
// CA 钉扎
// ---------------------------------------------------------------------------

/**
 * 取回网关的 CA 证书并装上信任。
 *
 * 顺序不能反：取 CA 的那一刻它还没被信任，普通请求必然 SSL 失败，
 * 形成「要信任它才能取到它」的死循环。所以用 `ctx.http.caCertificate`
 * 走放宽校验的通道取回，再由 `ctx.http.trustCa` 与 manifest 指纹比对。
 *
 * 中间人给的证书过不了指纹比对，因此放宽那一步不降低安全性。
 */
export async function pinGatewayCa(gateway: string, expectedFingerprint: string): Promise<void> {
    const expected = (expectedFingerprint ?? '').trim().toLowerCase();
    if (expected.length !== 64) {
        throw new GatewayError(0, 'INSTANCE_INVALID', '配对链接里的实例指纹格式不对，请整条复制');
    }
    const caBase64 = await ctx.http.caCertificate(`${gateway}${CA_PATH}`);
    // 把期望指纹交给宿主比对：宿主在 manifest 为 `runtime` 时只认这里给的值，
    // 指纹不符（中间人或粘错机器）会在这一步被拒
    ctx.http.trustCa(caBase64, expected);
    ctx.log.i('dsh', '已信任网关 CA');
}

// ---------------------------------------------------------------------------
// 配对 / 续期 / 探活
// ---------------------------------------------------------------------------

/** `native-pair` 的响应（`gateway.ts:2034-2054`） */
export interface PairResult {
    instanceId: string;
    deviceId: string;
    /** 长期设备凭据，**只用于续期**，不进业务请求 */
    deviceToken: string;
    deviceExpiresAt: number;
    /** 短期会话凭据，一切业务请求的凭据 */
    sessionToken: string;
    csrfToken: string;
    sessionExpiresAt: number;
}

function readPairResult(body: Record<string, unknown>): PairResult {
    const need = (key: string): string => {
        const value = body[key];
        if (typeof value !== 'string' || value.length === 0) {
            throw new GatewayError(0, 'BAD_RESPONSE', `配对响应缺少 ${key}`);
        }
        return value;
    };
    return {
        instanceId: need('instanceId'),
        deviceId: need('deviceId'),
        deviceToken: need('deviceToken'),
        deviceExpiresAt: typeof body.deviceExpiresAt === 'number' ? body.deviceExpiresAt : 0,
        sessionToken: need('sessionToken'),
        csrfToken: need('csrfToken'),
        sessionExpiresAt: typeof body.sessionExpiresAt === 'number' ? body.sessionExpiresAt : 0,
    };
}

/**
 * 用配对码换取设备与会话凭据。
 *
 * 配对码是一次性的：网关在成功后会**立刻关闭配对窗口**（`access.ts:319`），
 * 失败重试要用新码。所以这里不做内部重试。
 */
export async function pairDevice(
    gateway: string,
    pairingCode: string,
    label: string,
): Promise<PairResult> {
    const code = (pairingCode ?? '').trim();
    // 43 是网关的硬约束（`auth-pages.ts:33`），提前拦比让网关回 400 更好懂
    if (code.length !== 43) {
        throw new GatewayError(0, 'PAIRING_CODE_INVALID', '配对码应为 43 位，请从电脑端完整复制');
    }
    const body = await gatewayFetch<Record<string, unknown>>(gateway, {
        method: 'POST',
        path: `${AUTH_PREFIX}/native-pair`,
        body: { token: code, label },
    });
    return readPairResult(body);
}

/** `native-renew` 的响应：只有 session 与 csrf 会变，deviceToken 不变 */
export interface RenewResult {
    instanceId: string;
    deviceId: string;
    sessionToken: string;
    csrfToken: string;
    sessionExpiresAt: number;
}

/**
 * 用 deviceToken 续期会话。
 *
 * 会话 TTL 默认 8 小时、上限 24 小时（`config.ts:451`），且不会超过设备有效期
 * （`access.ts:286` 取 min）。所以长连接要定期续，不能指望一次配对一直用。
 *
 * **续期不轮换 deviceToken**，因此可以反复调；但并发续期会互相顶掉，
 * 调用方必须保证单飞。
 */
export async function renewSession(gateway: string, deviceToken: string): Promise<RenewResult> {
    const body = await gatewayFetch<Record<string, unknown>>(gateway, {
        method: 'POST',
        path: `${AUTH_PREFIX}/native-renew`,
        body: { deviceToken },
    });
    const need = (key: string): string => {
        const value = body[key];
        if (typeof value !== 'string' || value.length === 0) {
            throw new GatewayError(0, 'BAD_RESPONSE', `续期响应缺少 ${key}`);
        }
        return value;
    };
    return {
        instanceId: need('instanceId'),
        deviceId: need('deviceId'),
        sessionToken: need('sessionToken'),
        csrfToken: need('csrfToken'),
        sessionExpiresAt: typeof body.sessionExpiresAt === 'number' ? body.sessionExpiresAt : 0,
    };
}

/** 探活：只验设备凭据是否还有效，不产生新会话 */
export async function probeDevice(gateway: string, deviceToken: string): Promise<boolean> {
    try {
        await gatewayFetch<Record<string, unknown>>(gateway, {
            method: 'POST',
            path: `${AUTH_PREFIX}/native-probe`,
            body: { deviceToken },
        });
        return true;
    } catch (error) {
        if (error instanceof GatewayError && error.status === 401) return false;
        throw error;
    }
}

// ---------------------------------------------------------------------------
// 会话建立
// ---------------------------------------------------------------------------

/**
 * 建立一个已鉴权的 HTTP 会话。
 *
 * 做三件事，缺一不可：
 * 1. 建会话（隔离 cookie，不与会话外的请求串味）；
 * 2. 种 `__Host-dsh_ma_session`——网关只读 Cookie，不发这个就是全部 401；
 * 3. 设默认头 `Origin` / `Host`——连 WebSocket 也要，且 WS 缺 Origin 直接 403。
 *
 * CSRF 不设成默认头：只有写请求需要，而 GET 带着不会报错、但会让人
 * 误以为它总是必需的。由调用方显式传。
 */
export function openGatewaySession(
    gateway: string,
    sessionToken: string,
    name = GATEWAY_SESSION,
): string {
    const host = hostOf(gateway);
    // cookie 用不含端口的主机名，Host 头用带端口的完整 host：两者规则不同
    const cookieHost = cookieHostOf(gateway);
    const session = ctx.http.session(name, true);
    ctx.http.cookieSet(session, COOKIE_SESSION, sessionToken, cookieHost);
    ctx.http.cookieSet(session, COOKIE_DEVICE, sessionToken.slice(0, 8), cookieHost);
    ctx.http.setHeader(session, 'Origin', gateway);
    ctx.http.setHeader(session, 'Host', host);
    return session;
}

/**
 * 拼出 WebSocket 握手要用的 `Cookie` 头。
 *
 * ## 为什么自己拼而不让宿主代劳
 *
 * HTTP 与会话 cookie 走 `CookieJar`，但 WebSocket 是另一套 `OkHttpClient`，
 * 握手不会自动带上它们。而且手动种的 cookie 不带 `Secure` 属性时，
 * OkHttp 的 `Cookie.matches()` 会拒绝把它发往 `wss://`——所以按会话取
 * cookie 再匹配这条路走不通，直接取全部值拼头反而更可靠。
 *
 * 会话名固定为 [GATEWAY_SESSION]：它在 `openGatewaySession` 里刚种过，
 * 值一定是最新的 sessionToken。
 */
export function gatewayCookieHeader(): string {
    const all = ctx.http.cookieAll(GATEWAY_SESSION);
    const parts: string[] = [];
    for (const name of Object.keys(all)) {
        const value = all[name];
        if (typeof value === 'string' && value.length > 0) parts.push(`${name}=${value}`);
    }
    return parts.join('; ');
}

/** 清掉会话里的鉴权 cookie。删除槽位时就该调用它，避免残留会话被复用 */
export function closeGatewaySession(name = 'gateway'): void {
    ctx.http.cookieClear(name);
}

// ---------------------------------------------------------------------------
// 凭证存储
// ---------------------------------------------------------------------------

/**
 * 落库的网关接入状态。
 *
 * `deviceToken` 是长期凭据，`sessionToken` / `csrfToken` 是短期的。
 * 分开存是为了续期时只替换后两个，不碰设备凭据——设备凭据丢了就得重新配对。
 */
export interface StoredPairing {
    gateway: string;
    label: string;
    instanceId: string;
    deviceId: string;
    deviceToken: string;
    deviceExpiresAt: number;
    sessionToken: string;
    csrfToken: string;
    sessionExpiresAt: number;
}

const SLOT_PAIRING = 'gateway-pairing';

export function loadPairing(): StoredPairing | null {
    const raw = ctx.credentials.get(SLOT_PAIRING);
    if (typeof raw !== 'string' || raw.length === 0) return null;
    try {
        const parsed = JSON.parse(raw) as StoredPairing;
        // 老版本或半截数据：缺关键字段就当没配对，避免后续每步都报奇怪错误
        if (typeof parsed.gateway !== 'string' || typeof parsed.deviceToken !== 'string') return null;
        if (typeof parsed.sessionToken !== 'string') return null;
        return parsed;
    } catch {
        return null;
    }
}

export function savePairing(pairing: StoredPairing): void {
    ctx.credentials.set(SLOT_PAIRING, JSON.stringify(pairing));
}

export function clearPairing(): void {
    ctx.credentials.set(SLOT_PAIRING, null);
}

/** 会话是否已进入续期窗口（提前 5 分钟，留出一次失败重试的余量） */
export function sessionNeedsRenew(expiresAt: number, now = Date.now(), skewMs = 5 * 60_000): boolean {
    if (!Number.isFinite(expiresAt) || expiresAt <= 0) return true;
    return expiresAt - now <= skewMs;
}
