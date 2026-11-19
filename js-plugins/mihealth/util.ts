// 小米健康 JS 插件 —— 底层工具
//
// 本文件只放「与小米协议无关的通用能力」（字节编码、摘要、RC4、URL 处理），
// 协议实现放在 crypto.ts / client.ts / main.ts。
// 这样拆是为了让工具层以后能直接复制给其它插件用，
// 不必从一段混着业务常量的代码里往外挑。

/// <reference path="../types/index.d.ts" />

// ---------- UTF-8 编码 ----------
//
// QuickJS 没有 TextEncoder，而所有摘要都吃字节。这里手写 UTF-8 编码：
// 必须正确处理代理对（emoji、部分生僻字），否则密码里带特殊字符会算出错摘要，
// 症状是服务端稳定返回「账号或密码错误」，极难定位。

export function utf8Bytes(str: string): number[] {
    const out: number[] = [];
    for (let i = 0; i < str.length; i++) {
        let code = str.charCodeAt(i);

        // 高位代理：和后一位低位代理合成一个码点
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
            const low = str.charCodeAt(i + 1);
            if (low >= 0xdc00 && low <= 0xdfff) {
                code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                i++;
            }
        }

        if (code < 0x80) {
            out.push(code);
        } else if (code < 0x800) {
            out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        } else if (code < 0x10000) {
            out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        } else {
            out.push(
                0xf0 | (code >> 18),
                0x80 | ((code >> 12) & 0x3f),
                0x80 | ((code >> 6) & 0x3f),
                0x80 | (code & 0x3f),
            );
        }
    }
    return out;
}

// ---------- 摘要 ----------
//
// 走宿主的 ctx.crypto，不用纯 JS 实现：压缩函数在 JS 里跑比 native 慢一到两个
// 数量级，而且填充逻辑写错时的错误模式和「被风控拦截」完全一样，排查成本极高。

export function md5Hex(str: string): string {
    return ctx.crypto.md5Hex(utf8Bytes(str));
}

export function sha1Base64(bytes: number[]): string {
    return ctx.crypto.sha1Base64(bytes);
}

export function sha256BytesBase64(bytes: number[]): string {
    return ctx.crypto.sha256Base64(bytes);
}

// ---------- Base64 ----------

export function b64encode(bytes: number[]): string {
    return ctx.codec.base64Encode(bytes);
}

export function b64decode(str: string): number[] {
    return ctx.codec.base64Decode(str);
}

// ---------- 随机字节 ----------
//
// QuickJS 没有 crypto.getRandomValues。Math.random 的熵不够，但这里的用途是
// nonce 的随机部分与 deviceId —— 只要「不可预测且不重复」即可，
// 不用于密钥生成，因此可以接受。时间戳一并混入，保证同一毫秒内多次调用也不同。

let randomCounter = 0;

export function randomBytes(n: number): number[] {
    const out: number[] = new Array(n);
    for (let i = 0; i < n; i++) {
        out[i] = Math.floor(Math.random() * 256);
    }
    randomCounter = (randomCounter + 1) % 256;
    if (n > 0) out[0] ^= randomCounter;
    return out;
}

export function randomId(length: number, alphabet?: string): string {
    const chars = alphabet || 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let out = '';
    for (let i = 0; i < length; i++) {
        out += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return out;
}

// ---------- RC4-drop1024 ----------
//
// 小米用的是 RC4 变体：丢弃前 1024 字节密钥流。用标准 RC4 解出来是乱码
// 且**不会报错**，只会得到无法 JSON.parse 的字符串，所以这里必须严格照做。

export type Rc4Transform = (bytes: number[]) => number[];

export function rc4(keyBytes: number[]): Rc4Transform {
    const s: number[] = new Array(256);
    for (let i = 0; i < 256; i++) s[i] = i;

    let j = 0;
    for (let i = 0; i < 256; i++) {
        j = (j + s[i] + keyBytes[i % keyBytes.length]) & 0xff;
        const tmp = s[i];
        s[i] = s[j];
        s[j] = tmp;
    }

    let i2 = 0;
    let j2 = 0;

    // 推进状态但丢弃输出，共 1024 字节
    for (let k = 0; k < 1024; k++) {
        i2 = (i2 + 1) & 0xff;
        j2 = (j2 + s[i2]) & 0xff;
        const tmp = s[i2];
        s[i2] = s[j2];
        s[j2] = tmp;
    }

    // 同一状态对象连续加密多个值，与 Kotlin 版 A 步骤一致
    return function transform(bytes: number[]): number[] {
        const out: number[] = new Array(bytes.length);
        for (let n = 0; n < bytes.length; n++) {
            i2 = (i2 + 1) & 0xff;
            j2 = (j2 + s[i2]) & 0xff;
            const tmp = s[i2];
            s[i2] = s[j2];
            s[j2] = tmp;
            out[n] = bytes[n] ^ s[(s[i2] + s[j2]) & 0xff];
        }
        return out;
    };
}

// ---------- 定点数 ----------

/** 只接受有限数字；字符串里纯数字也认，其它一律 null */
export function asNumber(v: unknown): number | null {
    if (typeof v === 'number' && isFinite(v)) return v;
    if (typeof v === 'string' && v.trim() !== '') {
        const n = Number(v);
        if (isFinite(n)) return n;
    }
    return null;
}

/** 严格整数解析，供时间戳用（要挡住 "1.5e12" 这类） */
export function asLong(v: unknown): number | null {
    if (typeof v === 'number' && isFinite(v)) return Math.trunc(v);
    if (typeof v === 'string' && /^-?\d+$/.test(v.trim())) return parseInt(v.trim(), 10);
    return null;
}

// ---------- URL 构造 ----------

/**
 * 按 application/x-www-form-urlencoded 拼 body。
 * 顺序按传入对象的键插入顺序，Java/JS 对象都保序，服务端也不要求特定顺序。
 */
export function formEncode(params: Record<string, string | number | null | undefined>): string {
    return Object.keys(params)
        .map((k) => {
            const v = params[k];
            return encodeURIComponent(k) + '=' + encodeURIComponent(v == null ? '' : v);
        })
        .join('&');
}

export function parseQuery(url: string): Record<string, string> {
    const out: Record<string, string> = {};
    const idx = url.indexOf('?');
    if (idx < 0) return out;
    const query = url.slice(idx + 1);
    query.split('&').forEach((pair) => {
        if (!pair) return;
        const eq = pair.indexOf('=');
        const k = eq < 0 ? pair : pair.slice(0, eq);
        const v = eq < 0 ? '' : pair.slice(eq + 1);
        out[decodeURIComponent(k)] = decodeURIComponent(v);
    });
    return out;
}

/**
 * 把相对地址补成绝对地址。
 *
 * 必须按 RFC 3986 的相对引用规则解析，不能简单「origin + location」：
 * `result/check?sid=x` 这种不带前导斜杠的路径是相对于**当前路径的目录**的，
 * 拼错就会原地打转（表现为「重定向次数过多」），而且服务端已经开始
 * 下发 Set-Cookie 了，人很容易误判成鉴权问题。
 *
 * QuickJS 没有全局 URL 对象，所以这里手写。
 */
export function absoluteUrl(base: string, location: string): string {
    const loc = String(location || '').trim();
    if (!loc) return base;
    // 已带协议
    if (/^https?:\/\//i.test(loc)) return loc;
    // 协议相对：//host/path
    if (loc.startsWith('//')) return 'https:' + loc;

    const m = String(base || '').match(/^https?:\/\/[^/?#]+/);
    const origin = m ? m[0] : 'https://account.xiaomi.com';
    // 只取 base 的路径部分（去掉 query / hash）
    const basePath = String(base || '').slice(origin.length).replace(/[?#].*$/, '') || '/';

    // 绝对路径：直接用 origin
    if (loc.startsWith('/')) return origin + loc;

    // 相对路径：相对 base 所在目录
    if (loc.startsWith('?')) return origin + basePath + loc;
    const dir = basePath.slice(0, basePath.lastIndexOf('/') + 1) || '/';
    return origin + dir + loc;
}
