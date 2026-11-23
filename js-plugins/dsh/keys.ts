// X25519 密钥与字节编码：上游 `packages/crypto/src/index.ts` 的同构实现。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
//
// ## 为什么重写而不是整份搬过来
//
// 上游那份同时照顾 Node 与浏览器（`btoa` / `Buffer` 二选一），而这里跑在 QuickJS：
// 两者都没有，但宿主 `ctx.codec` 恰好提供了 URL-safe base64 与 SHA-256。
// 算法本身（X25519、截断取指纹）与上游完全一致，都会用 `fixtures/crypto/v1`
// 的官方向量逐字节验证——见 `main.ts` 的 `selfTest`。
//
// 唯一没搬的是 `deriveSharedKey`：它属于 WebRTC 数据通道那条路径，
// 首期固定走 Relay，用不到。真需要时再按上游 HKDF 参数补齐，而不是提前铺代码。

import { x25519 } from '@noble/curves/ed25519.js';

export interface KeyPair {
    publicKey: string;
    privateKey: string;
}

/** 生成一对 X25519 密钥，两半都以无补齐 base64url 表达 */
export function generateKeyPair(privateKeyBytes?: Uint8Array): KeyPair {
    const privateKey = privateKeyBytes ?? randomBytes(32);
    if (privateKey.byteLength !== 32) throw new Error('X25519 private keys must be 32 bytes');
    return {
        privateKey: toBase64Url(privateKey),
        publicKey: toBase64Url(x25519.getPublicKey(privateKey)),
    };
}

/** 无补齐 base64url；与上游 `toBase64Url` 输出逐字节一致 */
export function toBase64Url(bytes: Uint8Array): string {
    return ctx.codec.base64UrlEncode(Array.from(bytes));
}

/** 带不带 `=` 都能解；非法输入返回空数组（宿主行为），由调用方校验长度 */
export function fromBase64Url(value: string): Uint8Array {
    return Uint8Array.from(ctx.codec.base64UrlDecode(value));
}

/**
 * 设备指纹：公钥 SHA-256 的**前 6 字节**，大写十六进制。
 *
 * 截断是协议约定的：指纹只用于人工核对「两边显示的是不是同一台设备」，
 * 不做安全判定，因此不需要完整摘要。
 */
export function identityFingerprint(publicKey: string): string {
    const digest = ctx.crypto.sha256Bytes(Array.from(fromBase64Url(publicKey)));
    let out = '';
    for (let i = 0; i < 6; i += 1) out += digest[i].toString(16).padStart(2, '0');
    return out.toUpperCase();
}

/** 宿主随机源：返回 0~255 字节数组 */
export function randomBytes(length: number): Uint8Array {
    return Uint8Array.from(ctx.crypto.randomBytes(length));
}

/** UTF-8 字符串 → 字节。QuickJS 没有 `TextEncoder`，统一走宿主编解码 */
export function utf8(text: string): Uint8Array {
    return Uint8Array.from(ctx.codec.utf8Encode(text));
}

/** 字节 → UTF-8 字符串 */
export function fromUtf8(bytes: Uint8Array): string {
    return ctx.codec.utf8Decode(Array.from(bytes));
}
