// 小米健康 JS 插件 —— HTTP 与小米加密协议
//
// 这一层的职责边界：
// - client.js 管 cookie 存储与请求发出（宿主 ctx.http 只是运输层）
// - 本文件不管业务语义，只负责「把参数按小米要求签名后发出去」

import {
    absoluteUrl,
    asNumber,
    b64decode,
    b64encode,
    formEncode,
    md5Hex,
    randomBytes,
    rc4,
    sha1Base64,
    sha256BytesBase64,
    utf8Bytes,
} from './util.js';

export const ACCOUNT = 'https://account.xiaomi.com';
export const IDENTITY = 'https://account.xiaomi.com/identity';
export const SID = 'miothealth';
export const SDK_VERSION = 'accountsdk-18.8.15';

const JSON_PREFIX = '&&&START&&&';

// ---------- Cookie ----------
//
// cookie 由 client.js 自己管理（见其中的 jar），不经宿主 CookieJar。
// 早期手写版本确实因为 OkHttp 在自动重定向时剔除 Cookie 头而丢过会话，
// 但根因是「让宿主替我们跟跳转」。现在跳转也由插件自己逐跳发起，
// 每一跳的 Set-Cookie 当场收下、下一跳按域重新拼，不再有这个缺口。

// ---------- 小米签名 ----------
//
// 全流程最容易写错的地方，逐条对齐 Kotlin 实现：
//   1. rc4_hash__ 自身也要被 RC4 加密（它是在加密循环之前插入明文表的）
//   2. signature 与 _nonce 是明文追加，不参与 RC4
//   3. 排序集合是 params ∪ {rc4_hash__}，按 key 升序
//   4. RC4 必须 drop 1024，且同一状态对象连续加密

export function nonce() {
    // 8 随机字节 + int32BE(分钟级时间戳)，共 12 字节
    const bytes = randomBytes(8);
    const minutes = Math.floor(Date.now() / 60000);
    bytes.push((minutes >>> 24) & 0xff);
    bytes.push((minutes >>> 16) & 0xff);
    bytes.push((minutes >>> 8) & 0xff);
    bytes.push(minutes & 0xff);
    return b64encode(bytes);
}

export function sessionKey(ssecurity, nonceValue) {
    const a = b64decode(ssecurity);
    const b = b64decode(nonceValue);
    return sha256BytesBase64(a.concat(b));
}

/** 取纯 path，去掉 scheme/host/query */
function pathOf(url) {
    if (url.indexOf('://') >= 0) {
        const afterScheme = url.slice(url.indexOf('://') + 3);
        const slash = afterScheme.indexOf('/');
        const pathAndQuery = slash < 0 ? '/' : afterScheme.slice(slash);
        return pathAndQuery.split('?')[0];
    }
    return url.split('?')[0];
}

/**
 * 对参数签名。
 *
 * @return 形如 `a=..&b=..&signature=..&_nonce=..` 的 query 串（已是可拼接形态）
 */
export function signQuery(method, url, params, ssecurity) {
    const p = pathOf(url);
    const upper = method.toUpperCase();
    // nonce 必须只生成一次：sessionKey 用它算，_nonce 要把它作为明文原样带上。
    // 生成两次会导致服务端算出的 key 和我们对不上，表现为解密出乱码
    const nonceValue = nonce();
    const skey = sessionKey(ssecurity, nonceValue);

    // 步骤 A：明文表（value 非空白的项）
    const plain = {};
    Object.keys(params).forEach((k) => {
        const v = params[k];
        if (v == null) return;
        const s = String(v);
        if (s.trim() === '') return;
        plain[k] = s;
    });

    const sortedKeys = Object.keys(plain).sort();

    // rc4_hash__ = b64(SHA1(method & path & k=v & ... & skey))
    const hashSource =
        upper +
        '&' +
        p +
        '&' +
        sortedKeys.map((k) => k + '=' + plain[k]).join('&') +
        '&' +
        skey;
    const rc4Hash = sha1Base64(utf8Bytes(hashSource));

    // 步骤 B：rc4_hash__ 进入明文表，后续一起参与加密
    plain['rc4_hash__'] = rc4Hash;

    // 步骤 C：按 key 升序，用同一 RC4 状态连续加密
    const allKeys = Object.keys(plain).sort();
    const stream = rc4(b64decode(skey));
    const encrypted = {};
    allKeys.forEach((k) => {
        encrypted[k] = b64encode(stream(utf8Bytes(plain[k])));
    });

    // 步骤 D：signature = b64(SHA1(method & path & k=b64v & ... & skey))
    const sigSource =
        upper +
        '&' +
        p +
        '&' +
        allKeys.map((k) => k + '=' + encrypted[k]).join('&') +
        '&' +
        skey;
    const signature = sha1Base64(utf8Bytes(sigSource));

    // 步骤 E：signature 与 _nonce 明文追加
    const parts = allKeys.map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(encrypted[k]));
    parts.push('signature=' + encodeURIComponent(signature));
    parts.push('_nonce=' + encodeURIComponent(nonceValue));
    return { query: parts.join('&'), nonce: nonceValue };
}

/** 解密响应体：rc4(b64dec(sessionKey), b64dec(body)) 再按 UTF-8 解码 */
export function decryptBody(ssecurity, nonceValue, bodyB64) {
    const key = b64decode(sessionKey(ssecurity, nonceValue));
    const data = b64decode(bodyB64);
    const out = rc4(key)(data);
    return utf8Decode(out);
}

// ---------- UTF-8 解码 ----------

export function utf8Decode(bytes) {
    let out = '';
    let i = 0;
    while (i < bytes.length) {
        const b0 = bytes[i++] & 0xff;
        let cp;
        if (b0 < 0x80) {
            cp = b0;
        } else if (b0 < 0xe0) {
            cp = ((b0 & 0x1f) << 6) | (bytes[i++] & 0x3f);
        } else if (b0 < 0xf0) {
            cp = ((b0 & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
        } else {
            cp =
                ((b0 & 0x07) << 18) |
                ((bytes[i++] & 0x3f) << 12) |
                ((bytes[i++] & 0x3f) << 6) |
                (bytes[i++] & 0x3f);
        }
        out += String.fromCodePoint(cp);
    }
    return out;
}

// ---------- 响应解析 ----------

/** 剥掉 `&&&START&&&` 前缀后 JSON.parse */
export function parseMiapi(body) {
    const text = String(body || '');
    const idx = text.indexOf(JSON_PREFIX);
    const clean = (idx >= 0 ? text.slice(idx + JSON_PREFIX.length) : text).trim();
    try {
        return JSON.parse(clean);
    } catch (e) {
        return null;
    }
}

export function ua(deviceId, userId) {
    return (
        'MiFit/3.59.0 (Android 14; en_US) Resolution/1080x2400 Version/3.59.0 ' +
        'Stage/Production DeviceId/' + deviceId + ' UserId/' + (userId || '0')
    );
}

export function identityUa(deviceId, userId) {
    return (
        'MiHome/11.3.203 (com.xiaomi.mihome; build:11.3.203; Android 9) APP/com.xiaomi.mihome ' +
        'APPV/11.3.203 DeviceId/' + deviceId + ' UserId/' + (userId || '0') +
        ' Platform/Android Region/CN L/zh_CN'
    );
}

export { absoluteUrl, asNumber, formEncode, md5Hex };
