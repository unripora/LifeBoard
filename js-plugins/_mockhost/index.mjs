/**
 * 假宿主（mock host）：在 Node 里跑插件源码，不启动 QuickJS。
 *
 * ## 用途
 *
 * 写插件时不用每次都装进 App 试：在 Node 里 `import` 插件、拿一份内存版 `ctx`，
 * 就能调 `fetch` / `detailUi` / `login` 并断言返回结构。
 *
 * ## 它**不**模拟什么（重要，见 README）
 *
 * - QuickJS 的隔离、堆上限、中断
 * - 单段同步预算与故障停用
 * - 真实网络、TLS、证书钉扎
 * - 宿主线程模型（这里是普通异步，没有专用单线程与后台泵）
 *
 * 因此**通过假宿主不等于通过真机**。它只保证"逻辑与返回结构"正确。
 */

import { createHash, randomBytes } from 'node:crypto';

/**
 * 创建一份内存版 `ctx`。
 *
 * @param {object} options
 * @param {string} [options.pluginId]
 * @param {Record<string,string>} [options.credentials] 初始凭据槽
 * @param {Record<string,string>} [options.files] 初始文件（路径 → 文本）
 * @param {(req: {method: string, url: string, body?: string, headers?: Record<string,string>}) => any} [options.http]
 *        自定义 HTTP 处理；缺省返回 404
 * @param {string[]} [options.logs] 收集日志的数组（便于断言）
 */
export function createMockCtx(options = {}) {
    const pluginId = options.pluginId || 'mock.plugin';
    const credentials = new Map(Object.entries(options.credentials || {}));
    const files = new Map(Object.entries(options.files || {}));
    const logs = options.logs || [];
    const emitted = { stream: [], session: [] };
    const reported = { raw: [], queried: [], events: [] };
    const httpHandler = options.http || (() => ({ status: 404, body: '{}' }));

    /** 模拟一次 HTTP 往返，把结果整理成插件期望的响应结构 */
    async function request(method, url, body, headers) {
        const res = await httpHandler({ method, url, body, headers: headers || {} });
        const bodyText = typeof res.body === 'string' ? res.body : JSON.stringify(res.body ?? {});
        let json = null;
        try {
            json = JSON.parse(bodyText);
        } catch {
            json = null;
        }
        return {
            status: res.status ?? 200,
            ok: (res.status ?? 200) >= 200 && (res.status ?? 200) < 300,
            body: bodyText,
            bodyBase64: null,
            json,
            headers: res.headers || {},
            setCookie: res.setCookie || [],
        };
    }

    const ctx = {
        pluginId: () => pluginId,

        credentials: {
            get: (slot) => (credentials.has(slot) ? credentials.get(slot) : null),
            put: (slot, value) => {
                if (value == null) credentials.delete(slot);
                else credentials.set(slot, String(value));
            },
            set: (slot, value) => {
                if (value == null) credentials.delete(slot);
                else credentials.set(slot, String(value));
            },
            remove: (slot) => credentials.delete(slot),
            clear: () => credentials.clear(),
        },

        files: {
            read: (path) => (files.has(path) ? files.get(path) : null),
            write: (path, text) => {
                files.set(path, String(text ?? ''));
            },
            readBase64: (path) => {
                const text = files.get(path);
                return text == null ? null : Buffer.from(text, 'utf8').toString('base64');
            },
            writeBase64: (path, b64) => {
                files.set(path, Buffer.from(b64 || '', 'base64').toString('utf8'));
            },
            list: () => Array.from(files.keys()),
        },

        log: {
            d: (tag, msg) => logs.push({ level: 'd', tag, msg }),
            i: (tag, msg) => logs.push({ level: 'i', tag, msg }),
            w: (tag, msg) => logs.push({ level: 'w', tag, msg }),
            e: (tag, msg) => logs.push({ level: 'e', tag, msg }),
        },

        recentMetrics: (metricKey, days) => options.metrics || [],

        reportRawRecords: (metricKey, records, date) => {
            reported.raw.push({ metricKey, records, date });
        },
        reportMetricQueried: (metricKey, date) => {
            reported.queried.push({ metricKey, date });
        },
        report: (metricKey, value, at) => {
            reported.events.push({ metricKey, value, at });
        },

        http: {
            get: (url, opts) => request('GET', url, undefined, opts && opts.headers),
            post: (url, body, opts) => request('POST', url, body, opts && opts.headers),
            // 宿主真实签名是 `request(method, url, body, opts)`；
            // 早先这里写成 `(url, opts)`，把 method 当 url、把 url 当 opts，
            // 所有走 request 的插件在假宿主里都会拿到 404——测试看着「跑通了」，实际什么都没验。
            request: (method, url, body, opts) => {
                const o = opts || {};
                return request(String(method || 'GET').toUpperCase(), url, body, o.headers);
            },
            form: (url, fields, opts) => request('POST', url, JSON.stringify(fields), opts && opts.headers),
            postForm: (url, fields, opts) => request('POST', url, JSON.stringify(fields), opts && opts.headers),
            lastError: () => null,
            session: () => 'mock-session',
            setHeader: () => {},
            setUserAgent: () => {},
            setFollowRedirects: () => {},
            cookieGet: () => null,
            cookieSet: () => {},
            cookieRemove: () => {},
            cookieClear: () => {},
            cookieAll: () => [],
            caCertificate: async () => '',
            trustCa: () => {},
        },

        crypto: {
            md5Hex: (bytes) => createHash('md5').update(Buffer.from(bytes)).digest('hex').toUpperCase(),
            sha1Base64: (bytes) => createHash('sha1').update(Buffer.from(bytes)).digest('base64'),
            sha256Base64: (bytes) => createHash('sha256').update(Buffer.from(bytes)).digest('base64'),
            sha256Hex: (bytes) => createHash('sha256').update(Buffer.from(bytes)).digest('hex'),
            sha256Bytes: (bytes) => Array.from(createHash('sha256').update(Buffer.from(bytes)).digest()),
            randomBytes: (n) => Array.from(randomBytes(n)),
        },

        codec: {
            base64Encode: (bytes) => Buffer.from(bytes).toString('base64'),
            base64Decode: (text) => Array.from(Buffer.from(text || '', 'base64')),
            base64UrlEncode: (bytes) => Buffer.from(bytes).toString('base64url'),
            base64UrlDecode: (text) => Array.from(Buffer.from(text || '', 'base64url')),
            utf8Encode: (text) => Array.from(Buffer.from(text, 'utf8')),
            utf8Decode: (bytes) => Buffer.from(bytes).toString('utf8'),
        },

        ws: {
            connect: async () => 'mock-ws',
            recv: async () => ({ type: 'close', code: 1000 }),
            send: () => {},
            sendBinary: () => {},
            close: () => {},
            state: () => 'closed',
        },

        stream: {
            emit: (text) => emitted.stream.push(text),
        },

        session: {
            emit: (json) => emitted.session.push(json),
        },
    };

    return {
        ctx,
        /** 读当前凭据（断言用；真插件拿不到整个表） */
        snapshot: () => ({
            credentials: Object.fromEntries(credentials),
            files: Object.fromEntries(files),
            logs,
            emitted,
            reported,
        }),
    };
}

/**
 * 载入插件模块并注入 mock `ctx`。
 *
 * 注意：`ctx` 是插件读取的全局变量，所以这里改的是 `globalThis`。
 * 同一进程里并行跑两个插件会互相覆盖——需要隔离就分进程。
 *
 * @param {string} modulePath 绝对或相对路径（ESM）
 * @param {ReturnType<typeof createMockCtx>} mock
 */
export async function loadPlugin(modulePath, mock) {
    globalThis.ctx = mock.ctx;
    const mod = await import(modulePath);
    return mod;
}
