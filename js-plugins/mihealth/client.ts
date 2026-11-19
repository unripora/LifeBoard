// 小米健康 JS 插件 —— HTTP 客户端
//
// 本文件负责小米特有的参数组装、签名，以及**自己的 cookie 与重定向**。
//
// ## 为什么 cookie 不交给宿主
//
// 宿主的 CookieJar 与插件之间是一串隐式约定：同名 cookie 跨域覆盖、
// cookieGet 的参数顺序、重定向时自动补 Cookie 头。任何一处变了，插件就
// 莫名 401，而且从日志完全看不出原因——这类问题已经出现过多次。
// 现在 cookie 表就在本文件里，语义只由这里决定，换宿主实现也不受影响。
//
// 宿主只保留两件事：把请求发出去、把原始 body 和 Set-Cookie 给回来。

/// <reference path="../types/index.d.ts" />

import {
    ACCOUNT,
    decryptBody,
    formEncode,
    identityUa,
    parseMiapi,
} from './crypto.js';
import { absoluteUrl } from './util.js';

const SID = 'miothealth';

/** 数据接口固定端点 */
export const FITNESS_ENDPOINT = 'https://hlth.io.mi.com/app/v1/data/get_fitness_data_by_time';

/** 单次请求的时间窗口上限：服务端拒绝过宽区间 */
export const MAX_WINDOW_MILLIS = 2 * 24 * 60 * 60 * 1000;

/** 宿主的 `ctx.log` 级别名，与 `ctx.log.d/i/w/e` 一一对应 */
type LogLevel = 'd' | 'i' | 'w' | 'e';

/**
 * 本地日志。
 *
 * `main.ts` 里的 `log` 没有导出，而这里是最需要日志的地方（所有网络请求都经过它），
 * 为一处 import 把私有函数公开不划算，就地实现一份。
 * 同样地，日志失败绝不能影响业务。
 */
function log(level: LogLevel, message: string): void {
    try {
        ctx.log[level]('MiHealth', message);
    } catch (e) {
        /* 忽略 */
    }
}

/**
 * 把异常转成可读的一句话。
 *
 * 宿主的 Promise reject 可能给出 Error、字符串或普通对象，
 * 只取 `e.message` 会得到 undefined，日志里就成了「失败：undefined」。
 */
export function describeError(e: unknown): string {
    if (e == null) return '未知错误';
    if (typeof e === 'string') return e || '未知错误';
    if (typeof e === 'object') {
        const msg = (e as { message?: unknown }).message;
        if (typeof msg === 'string' && msg) return msg;
    }
    try {
        const s = String(e);
        if (s && s !== '[object Object]') return s;
    } catch (ignore) {
        /* 落到下面 */
    }
    return '未知错误';
}

/**
 * 从 URL 里裁出一段可以安全写进日志的形式。
 *
 * 小米的接口把 `_sign`、`qs`、`callback` 这些放在 query 上，而 `_sign` 是
 * 由 ssecurity 派生的凭证，写进日志再被导出就等于泄露。
 * 因此只保留**路径**和少数几个用于判断分支的参数。
 */
export function shortUrl(url: string): string {
    const s = String(url || '');
    const noQuery = s.split('?')[0];
    const keep = ['sid', '_flag', 'flag', 'icodeType'];
    const params: string[] = [];
    const query = s.includes('?') ? s.slice(s.indexOf('?') + 1) : '';
    for (const pair of query.split('&')) {
        const key = pair.split('=')[0];
        if (keep.includes(key)) params.push(pair);
    }
    const tail = params.length > 0 ? '?' + params.join('&') : '';
    // 只留最后两段路径，前面是固定的 https://account.xiaomi.com/identity 之类
    const parts = noQuery.split('/');
    return parts.slice(-2).join('/') + tail;
}

// ---------- 会话 ----------

/** 一次请求的入参。 */
export interface SendOptions {
    headers?: Record<string, string>;
    body?: string;
    contentType?: string;
    userAgent?: string;
    timeoutMs?: number;
    /** 设为 false 表示「只发一次」，3xx 原样返回，由调用方决定下一跳。 */
    followRedirects?: boolean;
}

export interface CreateSessionOptions {
    /**
     * 是否让这个会话成为后续请求的默认会话，默认 true。
     *
     * 续期这类「临时建一个会话、用完就丢」的场景要传 false，
     * 否则会把登录流程正在用的会话顶掉，后续请求莫名换了身份。
     */
    adoptAsDefault?: boolean;
}

/** 一条 cookie。`path` 保留用于唯一性判断与后续扩展。 */
interface StoredCookie {
    name: string;
    value: string;
    domain: string;
    path: string;
}

/** 会话对外暴露的能力。 */
export interface MiSession {
    send(method: string, url: string, options?: SendOptions): Promise<HttpResponse>;
    /** 按域读取；不传 domain 时取第一个同名（同名跨域返回值可能来自任意域）。 */
    cookie(name: string, domain?: string): string | null;
    setCookie(name: string, value: string, domain?: string): void;
    deviceId: string;
    sessionName: string;
    /** 当前会话里的 cookie 名（不含值），仅用于日志 */
    names(): string[];
}

/**
 * 一个 HTTP 会话。
 *
 * cookie 与重定向现在都交给宿主管理（`ctx.http.session`）：宿主给每个会话
 * 挂一个 CookieJar 到派生的 OkHttpClient 上，于是
 * - 每个请求自动带上该带的 cookie
 * - 重定向的每一跳自动带、自动收 Set-Cookie
 * - 跨域跳转时按 domain 规则取舍
 *
 * 这套行为与 OkHttp 默认、`requests.Session`、以及旧 Kotlin 插件版一致。
 * 此前的实现是手写 Cookie 头，结果在跨域重定向时 cookie 被 OkHttp 剔除，
 * 会话建不起来，服务端只回一句含糊的「非法请求」。
 *
 * @param deviceId 设备号，写进 UA 与伪装 cookie
 * @param name 会话名，同一插件内区分多个会话；不传用默认会话
 */
export function createSession(
    deviceId: string,
    name?: string,
    options?: CreateSessionOptions,
): MiSession {
    const opts = options || {};
    const adoptAsDefault = opts.adoptAsDefault !== false;

    // 会话仍然要向宿主登记：它是「一次运行的身份容器」，宿主用它做隔离与取消。
    // 但**cookie 不由宿主管**，见下面的 jar。
    let sessionName = 'default';
    try {
        sessionName = ctx.http.session(name || 'default', adoptAsDefault);
    } catch (e) {
        log('w', '创建 HTTP 会话失败，回退到默认会话：' + describeError(e));
    }

    // ---------- 插件自管的 cookie 表 ----------
    //
    // 为什么不交给宿主：宿主的 CookieJar 与插件之间是**隐式约定**
    // （同名 cookie 跨域覆盖、cookieGet 参数顺序、重定向时自动补 cookie），
    // 任何一处变了，插件就会莫名 401，且完全看不出原因。
    // 自己存一份，语义只由本文件决定。
    //
    // key = domain|path|name，与浏览器的 cookie 唯一性规则一致：
    // 同一个 name 可以在不同 domain 上并存，互不覆盖。
    const jar = new Map<string, StoredCookie>();

    function jarKey(domain: string, path: string, cname: string): string {
        return domain + '|' + path + '|' + cname;
    }

    /**
     * 拆出 hostname 与 pathname。
     *
     * 不用 `new URL`：QuickJS 没有这个全局对象，用了会在手机上直接抛
     * `URL is not defined`，而本地 Node 跑得好好的——是最难查的一类差异。
     */
    function splitUrl(url: string): { hostname: string; pathname: string } {
        const m = String(url || '').match(/^https?:\/\/([^/?#]+)([^?#]*)/i);
        if (!m) return { hostname: '', pathname: '/' };
        let host = m[1].toLowerCase();
        const at = host.indexOf('@');
        if (at >= 0) host = host.slice(at + 1);
        // 去掉端口
        const colon = host.indexOf(':');
        if (colon >= 0) host = host.slice(0, colon);
        return { hostname: host, pathname: m[2] || '/' };
    }

    /** 解析一条 Set-Cookie，写入 jar */
    function saveSetCookie(url: string, raw: string): void {
        const u = splitUrl(url);
        const parts = String(raw || '').split(';');
        const first = parts[0] || '';
        const eq = first.indexOf('=');
        if (eq <= 0) return;

        const cname = first.slice(0, eq).trim();
        const value = first.slice(eq + 1).trim();
        if (!cname) return;

        let domain = u.hostname;
        let cpath = '/';
        let expired = false;

        for (let i = 1; i < parts.length; i++) {
            const seg = parts[i].trim();
            const lower = seg.toLowerCase();
            if (lower.startsWith('domain=')) {
                domain = seg.slice(7).trim().replace(/^\./, '');
            } else if (lower.startsWith('path=')) {
                cpath = seg.slice(5).trim() || '/';
            } else if (lower.startsWith('max-age=')) {
                const n = parseInt(seg.slice(8).trim(), 10);
                if (!isNaN(n) && n <= 0) expired = true;
            } else if (lower.startsWith('expires=')) {
                const t = Date.parse(seg.slice(8).trim());
                if (!isNaN(t) && t <= Date.now()) expired = true;
            }
        }

        const key = jarKey(domain, cpath, cname);
        if (expired) {
            jar.delete(key);
        } else {
            jar.set(key, { name: cname, value, domain, path: cpath });
        }
    }

    /** 取该 URL 应发送的 cookie（domain 后缀匹配 + path 前缀匹配） */
    function cookiesFor(url: string): StoredCookie[] {
        const u = splitUrl(url);
        const out: StoredCookie[] = [];
        jar.forEach((c) => {
            const hostOk = u.hostname === c.domain || u.hostname.endsWith('.' + c.domain);
            const pathOk = u.pathname.indexOf(c.path) === 0;
            if (hostOk && pathOk) out.push(c);
        });
        return out;
    }

    // 伪装 cookie：服务端靠它们判断「是不是官方 App」。
    // 这类 cookie 不是服务端下发的，必须显式种进来。
    ['xiaomi.com', 'mi.com'].forEach((domain) => {
        const seed = (n: string, v: string) =>
            jar.set(jarKey(domain, '/', n), { name: n, value: v, domain, path: '/' });
        seed('sdkVersion', 'accountsdk-18.8.15');
        seed('deviceId', deviceId);
        seed('_locale', 'zh_CN');
    });

    async function send(method: string, url: string, options?: SendOptions): Promise<HttpResponse> {
        const opts = options || {};
        return await sendOnce(method, url, opts);
    }

    /**
     * 发一次请求并按需要自己跟随跳转。
     *
     * 每一跳都把最新的 Set-Cookie 收进 jar，下一跳再按 domain 规则重新拼，
     * 因此跨域跳转不会丢会话。
     */
    async function sendOnce(method: string, url: string, opts: SendOptions): Promise<HttpResponse> {
        // followRedirects:false 的语义是「只发一次，3xx 原样返回给调用方」，
        // 由调用方自己决定下一跳（submit2FA 就是这么用的）。
        // 早先把 false 当成 maxHops=1，结果第一跳的 302 也被当成
        // 「跳转次数用完了」而抛错，直接把整条认证链掐断。
        const follow = opts.followRedirects !== false;
        const maxHops = follow ? 10 : 1;
        let current = url;
        let currentMethod = method;
        let currentBody = opts.body;

        for (let hop = 0; hop < maxHops; hop++) {
            const res = await sendRaw(currentMethod, current, opts, currentBody);

            // 收 Set-Cookie（宿主已把多值拆成数组，值里带逗号也不会被切错）
            const list = Array.isArray(res.setCookie) ? res.setCookie : [];
            list.forEach((raw) => saveSetCookie(current, raw));

            const next = (res.headers && res.headers['location']) || '';
            const isRedirect = res.status >= 300 && res.status < 400 && next;
            if (isRedirect) {
                log(
                    'd',
                    'hop' + hop + ' ' + res.status +
                        ' setCookie=' + list.length +
                        ' jar=' + jar.size +
                        ' from=' + shortUrl(current) +
                        ' to=' + shortUrl(next),
                );
            }
            if (!isRedirect || !follow) return res;

            const resolved = absoluteUrl(current, next);
            // 跳回同一个地址说明这一跳靠 cookie 推进；URL 不变却继续跳就是死循环
            if (resolved === current) {
                throw new Error('跳转地址与当前相同，无法前进：' + shortUrl(current));
            }
            current = resolved;
            // 303 与 302/301 的语义：跳转后一律 GET，且不带 body
            if (res.status === 303 || currentMethod !== 'GET') {
                currentMethod = 'GET';
                currentBody = undefined;
            }
        }
        throw new Error('重定向次数过多：' + shortUrl(url));
    }

    async function sendRaw(
        method: string,
        url: string,
        opts: SendOptions,
        body: string | undefined,
    ): Promise<HttpResponse> {
        const headers: Record<string, string> = Object.assign({}, opts.headers);
        const argv: HttpOptions = {
            headers,
            // 永远不让宿主跟随：它跟随会丢掉我们拼的 Cookie 头
            followRedirects: false,
        };
        if (opts.contentType) argv.contentType = opts.contentType;

        // 自己拼 Cookie 头。显式传入的 Cookie（例如数据接口只认三件套）
        // 优先于 jar，避免两份来源打架
        if (!headers.Cookie && !headers.cookie) {
            const list = cookiesFor(url);
            if (list.length) headers.Cookie = list.map((c) => c.name + '=' + c.value).join('; ');
        }
        if (opts.userAgent) argv.userAgent = opts.userAgent;
        if (opts.timeoutMs) argv.timeoutMs = opts.timeoutMs;

        let res: HttpResponse;
        try {
            if (method === 'POST') {
                res = await ctx.http.post(url, body || '', argv, sessionName);
            } else {
                res = await ctx.http.get(url, argv, sessionName);
            }
        } catch (e) {
            log('e', method + ' ' + shortUrl(url) + ' 请求失败：' + describeError(e));
            throw e;
        }

        log('d', method + ' ' + shortUrl(url) + ' -> ' + res.status);
        return res;
    }

    /**
     * 按域读取。
     *
     * 不传 domain 时**不**做「随便找一个同名」的兜底：那正是
     * `.hlth.io.mi.com` 上的 `serviceToken=miothealth` 标记冒充真令牌的来源。
     * 同名 cookie 在不同域上是不同的东西，必须显式区分。
     */
    function cookie(cname: string, domain?: string): string | null {
        if (domain) {
            const d = String(domain).replace(/^\./, '');
            const hit = jar.get(jarKey(d, '/', cname));
            if (hit) return hit.value;
            // path 不一定是 '/'，再按域扫一遍
            let found: string | null = null;
            jar.forEach((c) => {
                if (c.name === cname && c.domain === d && found == null) found = c.value;
            });
            return found;
        }
        let found: string | null = null;
        jar.forEach((c) => {
            if (c.name === cname && found == null) found = c.value;
        });
        return found;
    }

    /**
     * 往会话里种一个 cookie。
     *
     * 用于「服务端没下发、但我们必须自称有」的凭据，例如用 passToken 续期时
     * 要把历史凭据先放进会话，服务端才会认这是一次已认证请求。
     */
    function setCookie(name: string, value: string, domain?: string): void {
        if (!name || value == null || value === '') return;
        const d = String(domain || 'xiaomi.com').replace(/^\./, '');
        jar.set(jarKey(d, '/', name), { name, value, domain: d, path: '/' });
    }

    // deviceId 要一并带出：后续跳转与取令牌时，UA 里的 deviceId 必须与登录时一致，
    // 服务端会校验这一点，换了就 401
    //
    // names 给日志用：只回 cookie 名，不含值——日志会被整份导出，
    // 值是等价的密码，不能写进去。
    return {
        send,
        cookie,
        setCookie,
        deviceId,
        sessionName,
        names: () => {
            // 不用 jar.values() 这类迭代器：写成最朴素的遍历，少一个兼容性变数
            const seen: Record<string, boolean> = {};
            const out: string[] = [];
            jar.forEach((c) => {
                if (!seen[c.name]) {
                    seen[c.name] = true;
                    out.push(c.name);
                }
            });
            return out;
        },
    };
}

// ---------- 小米 passport 接口 ----------

/**
 * 取验证码图片。
 *
 * 随机数必须每次重新生成：中间层会按 query 缓存，复用同一个值会一直拿到同一张图。
 */
export async function fetchCaptcha(session: MiSession): Promise<string> {
    const res = await session.send(
        'GET',
        ACCOUNT + '/pass/getCode?icodeType=login&' + Math.random(),
        { headers: { Referer: ACCOUNT + '/' } },
    );
    // 图片是二进制，宿主只在 bodyBase64 里给内容（body 对二进制是空串，
    // 按文本读会把字节解成乱码，base64Decode 出来必然不是图片）
    const b64 = res.bodyBase64;
    if (!res.ok || !b64) return '';

    const bytes = ctx.codec.base64Decode(b64);
    if (!bytes || bytes.length < 4) return '';
    const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8;
    const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
    if (!isJpeg && !isPng) return '';
    // 注意：PNG 也用 image/jpeg 前缀，与 Kotlin 版保持一致
    return 'data:image/jpeg;base64,' + b64;
}

/**
 * 握手：拿 _sign / qs / callback。
 *
 * 直接返回解析后的对象，与 Python 的 service_login 一致。
 * 解析只用插件自己的 parseMiapi（会剥 &&&START&&& 前缀），
 * 不读宿主给的 json 字段——宿主是严格 JSON 解析，遇到前缀恒为 null。
 */
export async function serviceLogin(session: MiSession): Promise<any> {
    const res = await session.send(
        'GET',
        ACCOUNT + '/pass/serviceLogin?sid=miothealth&_json=true',
        { headers: { Referer: ACCOUNT + '/' } },
    );
    const json = parseMiapi(res.body);
    if (!json || !json._sign) {
        const raw = String((res && res.body) || '');
        log('e', 'serviceLogin 未拿到 _sign：status=' + ((res && res.status) || '无') +
            ' body长度=' + raw.length +
            ' 开头=' + JSON.stringify(raw.slice(0, 160)));
    }
    return json;
}

/** `serviceLoginRaw` 的返回值，额外带出原始响应供排查。 */
export interface ServiceLoginRawResult {
    json: any;
    raw: string;
    status: number;
}

/**
 * 握手并返回原始响应体，供排查用。
 *
 * 与 [serviceLogin] 的唯一区别是额外把 body 交出来：2FA 之后重做这一步时
 * 服务端理应返回 `ssecurity`，但可能返回了别的形态（错误码、空对象、
 * 非预期字段名）。只看解析后的对象无法区分这些情况。
 *
 * ## 不要再手动塞 Cookie 头
 *
 * 早先这里在传了 `asUser` 时执行 `headers.Cookie = 'userId=' + asUser`。
 * 那是个严重错误：它会**覆盖**会话 CookieJar 自动带上的整组认证 cookie
 * （passToken / cUserId / passInfo…），服务端拿到的只剩一个孤零零的 userId，
 * 于是按未认证处理，稳定返回不带 ssecurity 的响应。
 *
 * 参考实现（API.md 第 ②a 步）的做法恰恰相反：
 * 「带 cookie {passToken, cUserId, userId} 重发 serviceLogin → 返回 ssecurity」。
 * 关键是**带上整组 jar 里的 cookie**，而不是自己拼一个。
 *
 * 而 2FA 完成后 userId 本身就已经在 jar 里了（服务端下发），无需插件再种。
 */
export async function serviceLoginRaw(session: MiSession): Promise<ServiceLoginRawResult> {
    const res = await session.send(
        'GET',
        ACCOUNT + '/pass/serviceLogin?sid=miothealth&_json=true',
        { headers: { Referer: ACCOUNT + '/' } },
    );
    const json = parseMiapi(res.body);
    // 注意：这里**不判断 _sign**。serviceLoginRaw 既用于首次握手，也用于
    // 2FA 完成后重做——重做时服务端本来就不下发 _sign，只回 ssecurity/
    // psecurity/passToken/location。按首次握手的标准判失败会打出误导性的
    // 「握手响应异常」，而实际上那次响应是完全正常的。
    if (!json) {
        const raw = String((res && res.body) || '');
        log('e', 'serviceLogin 响应无法解析：status=' + ((res && res.status) || '无') +
            ' body长度=' + raw.length +
            ' 开头=' + JSON.stringify(raw.slice(0, 160)));
    }
    return { json, raw: String(res.body || ''), status: res.status };
}

/**
 * 提交账号密码。
 *
 * 密码哈希必须是**单次** MD5 转大写。双重 MD5 会稳定返回 70016，
 * 且错误信息看起来像风控，很容易误判。
 */
export async function serviceLoginAuth2(
    session: MiSession,
    account: string,
    passwordHash: string,
    handshake: any,
    captCode: string,
): Promise<any> {
    const body: Record<string, string> = {
        user: account,
        hash: passwordHash,
        sid: 'miothealth',
        jsondata: '',
        _json: 'true',
        _sign: handshake._sign,
        qs: handshake.qs || '',
        callback: handshake.callback || '',
        locale: 'zh_CN',
    };
    if (captCode) body.captCode = captCode;

    const res = await session.send('POST', ACCOUNT + '/pass/serviceLoginAuth2', {
        headers: {
            Referer: ACCOUNT + '/',
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: formEncode(body),
        contentType: 'application/x-www-form-urlencoded',
        // 不能自动跟随：登录结果里的 location 是下一步要走的 STS 回调，
        // 跟着跳会让 serviceToken 下发在中间跳，插件再也拿不到
        followRedirects: false,
    });
    return parseMiapi(res.body);
}

/**
 * 是否为可用的 serviceToken 值。
 *
 * 必须排掉两类假值：
 * - `EXPIRED`：服务端用它表示令牌已失效
 * - `miothealth`：这是 `.hlth.io.mi.com` 上名为 serviceToken 的 **sid 标记**，
 *   不是真令牌。参考实现 API.md 明确警告过这一点，而宿主的
 *   `cookieGet` 只按名字取第一个，很可能先命中它，
 *   于是请求头变成 `serviceToken=miothealth`，数据接口必然 401。
 */
function isUsableServiceToken(value: string | null): boolean {
    return !!value && value !== 'EXPIRED' && value !== SID;
}

/**
 * 取 serviceToken：优先从本次响应的 Set-Cookie 里读，
 * 因为那是服务端刚下发、与本跳严格配套的值；读会话 cookie 会被同名假值干扰。
 */
export async function fetchServiceToken(
    session: MiSession,
    location: string,
    userId: string | number,
): Promise<string | null> {
    // 这一步不能自动跟随：serviceToken 是在该跳的 Set-Cookie 里下发的，
    // 自动跟随后只会看到最终响应的 header，中间跳的那条就丢了
    const res = await session.send('GET', absolute(ACCOUNT, location), {
        userAgent: identityUa(session.deviceId, userId),
        followRedirects: false,
    });

    // ① 本次响应的 Set-Cookie（最可靠）
    const setCookie = Array.isArray(res.setCookie) ? res.setCookie : [];
    for (let i = 0; i < setCookie.length; i++) {
        const m = String(setCookie[i] || '').match(/serviceToken=([^;]+)/);
        if (m && isUsableServiceToken(m[1])) return m[1];
    }

    // ② 只从 STS 域取会话里的值；`.hlth.io.mi.com` 的同名 cookie 是 sid 标记。
    const fromCookie = session.cookie('serviceToken', 'sts-hlth.io.mi.com') ||
        session.cookie('serviceToken', 'sts.api.io.mi.com');
    if (isUsableServiceToken(fromCookie)) return fromCookie;

    // ③ 响应正文兜底（少数跳会把 token 写在 body 里）
    const m2 = String(res.body || '').match(/serviceToken=([^;&"'\s]+)/);
    if (m2 && isUsableServiceToken(m2[1])) return m2[1];
    return null;
}

function absolute(base: string, location: string): string {
    if (/^https?:\/\//i.test(location)) return location;
    if (location.startsWith('//')) return 'https:' + location;
    const origin = base.match(/^https?:\/\/[^/]+/);
    const prefix = origin ? origin[0] : ACCOUNT;
    return location.startsWith('/') ? prefix + location : prefix + '/' + location;
}

export { decryptBody };
