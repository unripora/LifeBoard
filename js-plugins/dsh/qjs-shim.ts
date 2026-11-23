// QuickJS 缺失的少量 Web 全局对象，在本插件里补齐。
//
// ## 为什么需要它
//
// 上游 `@lukeburns/clatterjs` 在**模块初始化时**就执行
// `new TextEncoder().encode(...)`（见其 `symmetricState.js`、`constants.js`），
// `@noble/*` 也可能在生成随机数时读 `globalThis.crypto`。
// QuickJS 一个都没有，不补就会在「加载插件」阶段直接抛 `TextEncoder is not defined`——
// 表现为整个包加载失败，而不是某个调用返回错误，排查时很容易误判成语法问题。
//
// ## 为什么能在这里补
//
// 宿主的 `ctx.*` 是宿主函数，在**执行入口模块之前**就已注册为全局，
// 因此本文件在模块顶层就能同步调到 `ctx.codec` / `ctx.crypto`。
// esbuild 用 `--inject` 把它插到产物最前面，保证先于任何依赖模块求值。
//
// ## 语义对齐
//
// `ctx.codec.utf8Encode/utf8Decode` 与 `TextEncoder/TextDecoder` 的 UTF-8 行为一致
// （非法序列解成 U+FFFD，不抛错），因此可以直接转接，不需要另写编码器。

/// <reference path="../types/index.d.ts" />

type ByteSource = ArrayLike<number>;

class QjsTextEncoder {
    readonly encoding = 'utf-8';

    encode(text: string = ''): Uint8Array {
        return Uint8Array.from(ctx.codec.utf8Encode(text));
    }
}

class QjsTextDecoder {
    readonly encoding = 'utf-8';

    decode(bytes?: ByteSource): string {
        if (bytes === undefined) return '';
        return ctx.codec.utf8Decode(Array.from(bytes, (b) => b & 0xff));
    }
}

/** 宿主 `ctx.codec` 用的是标准 base64（带 `=` 补齐），与 `btoa`/`atob` 一致 */
function qjsBtoa(binary: string): string {
    const bytes: number[] = [];
    for (let i = 0; i < binary.length; i += 1) bytes.push(binary.charCodeAt(i) & 0xff);
    return ctx.codec.base64Encode(bytes);
}

function qjsAtob(base64: string): string {
    const bytes = ctx.codec.base64Decode(base64);
    let out = '';
    for (let i = 0; i < bytes.length; i += 1) out += String.fromCharCode(bytes[i]);
    return out;
}

/**
 * 只实现 `getRandomValues`。
 *
 * 上游拿到随机数后只做 `Uint8Array` 填充，用不到 `subtle` / `randomUUID`。
 * 实现它而不是假装完整，是为了让「缺了某个算法」以明确的 TypeError 暴露，
 * 而不是某天静默返回错误长度。
 */
const qjsCrypto = {
    getRandomValues<T extends ByteSource>(target: T): T {
        const view = target as unknown as { length: number };
        if (view.length > 65536) throw new Error('getRandomValues: 单次最多 65536 字节');
        const bytes = ctx.crypto.randomBytes(view.length);
        for (let i = 0; i < view.length; i += 1) {
            (target as unknown as number[])[i] = bytes[i];
        }
        return target;
    },
};

const globals = globalThis as unknown as Record<string, unknown>;
if (typeof globals.TextEncoder !== 'function') globals.TextEncoder = QjsTextEncoder;
if (typeof globals.TextDecoder !== 'function') globals.TextDecoder = QjsTextDecoder;
if (typeof globals.btoa !== 'function') globals.btoa = qjsBtoa;
if (typeof globals.atob !== 'function') globals.atob = qjsAtob;
if (!globals.crypto) globals.crypto = qjsCrypto;

export {};
