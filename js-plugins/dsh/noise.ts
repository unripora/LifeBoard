// 上游 `@dsh-remote/crypto` 的 Noise IK 会话实现，**逻辑逐行照搬**。
//
// 来源：https://github.com/liguobao/ds-harness-remote
// 路径：`packages/crypto/src/noise.ts`
// 提交：eb907b2e1e4a591a2941fae08df44e7c165fdfa7
// 许可：MIT（Copyright (c) 2026 DSH Remote contributors）
//
// ## 只有两处改动，均为 import
//
// 1. `randomBytes` 不再取 `@noble/ciphers/webcrypto`（依赖浏览器 `crypto.getRandomValues`），
//    改为本文件末尾的 `randomBytes`，走宿主 `ctx.crypto.randomBytes`。
// 2. `fromBase64Url` 改从 `./keys.js` 取（上游 `index.ts` 里的同一实现，见该文件说明）。
//
// 之所以不改算法部分：握手必须与对端逐字节一致，任何「顺手优化」都会让
// 两侧协商出的密钥不同，而表现只是「连上了但消息收不到」，极难定位。

import {
    NqHandshake,
    chachaPoly,
    noiseIk,
    sha256H,
} from '@lukeburns/clatterjs';
import type { TransportState } from '@lukeburns/clatterjs';
import { fromBase64Url } from './keys.js';

export const NOISE_IK_PROTOCOL = 'Noise_IK_25519_ChaChaPoly_SHA256' as const;

export interface NoiseIkSessionOptions {
    role: 'initiator' | 'responder';
    localPrivateKey: string;
    localPublicKey: string;
    remotePublicKey: string;
    prologue: Uint8Array;
    random?: (length: number) => Uint8Array;
    /** Optional byte-identical ChaChaPoly accelerator. Nonces remain owned by Noise. */
    encryptChaChaPoly?: (key: Uint8Array, nonce: bigint, ad: Uint8Array, plaintext: Uint8Array) => Uint8Array | undefined;
}

/**
 * A narrow DSH Remote wrapper around a maintained Noise framework.
 *
 * It intentionally exposes only the fixed v1 IK suite and verifies the
 * transcript's remote static key before transport messages can be used.
 */
export class NoiseIkSession {
    private handshake?: NqHandshake;
    private readonly expectedRemoteStatic: Uint8Array;
    private transport?: TransportState;
    private destroyed = false;

    constructor(options: NoiseIkSessionOptions) {
        const localPrivate = requireKey(options.localPrivateKey, 'local private key');
        const localPublic = requireKey(options.localPublicKey, 'local public key');
        this.expectedRemoteStatic = requireKey(options.remotePublicKey, 'remote public key');
        const initiator = options.role === 'initiator';
        this.handshake = new NqHandshake(noiseIk(), {
            prologue: Uint8Array.from(options.prologue),
            initiator,
            s: { secretKey: localPrivate, publicKey: localPublic },
            ...(initiator ? { rs: Uint8Array.from(this.expectedRemoteStatic) } : {}),
            cipher: options.encryptChaChaPoly ? {
                ...chachaPoly,
                encrypt(key, nonce, ad, plaintext, out) {
                    const encrypted = options.encryptChaChaPoly!(key, nonce, ad, plaintext);
                    if (encrypted === undefined) return chachaPoly.encrypt(key, nonce, ad, plaintext, out);
                    if (encrypted.byteLength !== plaintext.byteLength + chachaPoly.tagLen) {
                        throw new NoiseSessionError('INVALID_ENCRYPTION_OUTPUT', 'ChaChaPoly accelerator returned an invalid length.');
                    }
                    out.set(encrypted);
                },
            } : chachaPoly,
            hash: sha256H,
            rng: options.random ?? randomBytes,
        });
        if (this.handshake.getName() !== NOISE_IK_PROTOCOL) {
            throw new NoiseSessionError('NOISE_SUITE_MISMATCH', 'The Noise provider selected an unexpected cipher suite.');
        }
    }

    get protocol(): typeof NOISE_IK_PROTOCOL { return NOISE_IK_PROTOCOL; }

    get complete(): boolean { return this.transport !== undefined; }

    get canWriteHandshake(): boolean {
        this.assertLive();
        return !this.complete && this.handshake!.isWriteTurn();
    }

    writeHandshake(payload = new Uint8Array()): Uint8Array {
        this.assertHandshake(false);
        const output = new Uint8Array(payload.byteLength + this.handshake!.getNextMessageOverhead());
        const length = this.handshake!.writeMessage(payload, output);
        this.finishIfReady();
        return output.slice(0, length);
    }

    readHandshake(message: Uint8Array): Uint8Array {
        this.assertHandshake(true);
        const output = new Uint8Array(message.byteLength);
        const length = this.handshake!.readMessage(message, output);
        this.verifyRemoteStatic();
        this.finishIfReady();
        return output.slice(0, length);
    }

    encrypt(plaintext: Uint8Array): Uint8Array {
        this.assertLive();
        if (this.transport === undefined) throw new NoiseSessionError('NOISE_NOT_READY', 'The Noise handshake is not complete.');
        return this.transport.sendVec(plaintext);
    }

    decrypt(ciphertext: Uint8Array): Uint8Array {
        this.assertLive();
        if (this.transport === undefined) throw new NoiseSessionError('NOISE_NOT_READY', 'The Noise handshake is not complete.');
        return this.transport.receiveVec(ciphertext);
    }

    sendingCounter(): bigint {
        if (this.transport === undefined) return 0n;
        return this.transport.sendingNonce();
    }

    receivingCounter(): bigint {
        if (this.transport === undefined) return 0n;
        return this.transport.receivingNonce();
    }

    destroy(): void {
        if (this.destroyed) return;
        this.destroyed = true;
        this.expectedRemoteStatic.fill(0);
        this.transport = undefined;
        // clatterjs has no key erase API. Release its handshake and transport owners.
        this.handshake = undefined;
    }

    private finishIfReady(): void {
        if (!this.handshake!.isFinished()) return;
        this.verifyRemoteStatic();
        this.transport = this.handshake!.finalize();
    }

    private verifyRemoteStatic(): void {
        const actual = this.handshake!.getRemoteStatic();
        if (actual === undefined || !constantTimeEqual(actual, this.expectedRemoteStatic)) {
            this.destroy();
            throw new NoiseSessionError('PEER_IDENTITY_MISMATCH', 'The Noise peer static key does not match local trust.');
        }
    }

    private assertHandshake(reading: boolean): void {
        this.assertLive();
        if (this.complete) throw new NoiseSessionError('NOISE_ALREADY_READY', 'The Noise handshake is already complete.');
        if (this.handshake!.isWriteTurn() === reading) {
            throw new NoiseSessionError('NOISE_HANDSHAKE_ORDER', 'The Noise handshake message arrived out of order.');
        }
    }

    private assertLive(): void {
        if (this.destroyed) throw new NoiseSessionError('NOISE_DESTROYED', 'The Noise session is closed.');
    }
}

export class NoiseSessionError extends Error {
    constructor(readonly code: string, message: string) { super(message); }
}

export function createNoisePrologue(connectionId: string, hostDeviceId: string, clientDeviceId: string): Uint8Array {
    for (const [name, value] of Object.entries({ connectionId, hostDeviceId, clientDeviceId })) {
        if (value.length === 0 || value.includes('\0')) throw new TypeError(`${name} is not safe for a Noise prologue`);
    }
    // 不用 `TextEncoder`：QuickJS 没有它，宿主 `ctx.codec` 才是唯一出口
    return Uint8Array.from(ctx.codec.utf8Encode(
        `DSH-REMOTE\0v=1\0connection=${connectionId}\0host=${hostDeviceId}\0client=${clientDeviceId}`,
    ));
}

function requireKey(value: string, label: string): Uint8Array {
    const key = fromBase64Url(value);
    if (key.byteLength !== 32) throw new NoiseSessionError('INVALID_KEY', `${label} must be a 32-byte X25519 key.`);
    return Uint8Array.from(key);
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
    if (left.byteLength !== right.byteLength) return false;
    let different = 0;
    for (let index = 0; index < left.byteLength; index += 1) different |= left[index]! ^ right[index]!;
    return different === 0;
}

/** 宿主随机源：`ctx.crypto.randomBytes` 直接返回 0~255 的字节数组 */
function randomBytes(length: number): Uint8Array {
    return Uint8Array.from(ctx.crypto.randomBytes(length));
}
