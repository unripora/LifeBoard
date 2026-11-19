/// <reference path="../index.d.ts" />

// 负向探针：这个文件里的每一行都**必须**让 TS 报错。
//
// 插件跑在 QuickJS 里，不是 Node 也不是浏览器。下面这些"顺手就用"的全局对象
// 在本地 Node 调试时完全正常，装到手机上直接抛 `xxx is not defined`，
// 而且报错点离真正原因很远。
//
// 这份文件由 `npm run typecheck:negative` 校验：
// 只要有一行不再报错，说明 lib 配置被放宽了（比如误加了 "DOM" 或 node types），
// 脚本会失败。

export function mustNotCompile(): void {
    // @ts-expect-error QuickJS 没有 fetch，网络只能用 ctx.http
    void fetch('https://example.com');

    // @ts-expect-error QuickJS 没有 URL
    void new URL('https://example.com/a?b=1');

    // @ts-expect-error QuickJS 没有 URLSearchParams
    void new URLSearchParams('a=1');

    // @ts-expect-error QuickJS 没有 TextEncoder
    void new TextEncoder().encode('a');

    // @ts-expect-error QuickJS 没有 TextDecoder
    void new TextDecoder().decode(new Uint8Array());

    // @ts-expect-error QuickJS 没有 Buffer
    void Buffer.from('a');

    // @ts-expect-error QuickJS 没有 atob
    void atob('YQ==');

    // @ts-expect-error QuickJS 没有 btoa
    void btoa('a');

    // @ts-expect-error QuickJS 没有 process
    void process.env;

    // @ts-expect-error QuickJS 没有 require
    void require('fs');
}

// 正向对照：ctx 上不该存在的字段同样必须报错。
// 这类错字在 JS 里会静默变成 undefined，一路飘到业务逻辑深处。
export function mustNotCompileCtx(): void {
    // @ts-expect-error 正确名字是 ctx.http.postForm
    void ctx.http.postFrom('https://example.com', {});

    // @ts-expect-error 正确名字是 ctx.reportMetricQueried
    void ctx.reportQuery('STEPS', '2026-01-01');

    // @ts-expect-error 没有这个能力
    void ctx.http.download('https://example.com');
}
