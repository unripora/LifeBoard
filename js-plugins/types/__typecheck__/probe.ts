// 类型声明的自检探针。
//
// 目的不是成为插件，而是证明三件事：
// 1. ctx 的每个能力都能按声明调用（字段名、参数、返回值对得上宿主）；
// 2. 契约返回类型能约束 detailUi / login / fetch 的写法；
// 3. QuickJS 里不存在的全局对象（URL、Buffer、TextEncoder）会被 TS 拦下。
//
// 第 3 点无法用「编译通过」证明（它本来就该报错），
// 所以放在单独的文件里，由 npm run typecheck:negative 验证。

/// <reference path="../index.d.ts" />

import type { UiNode } from '../index.js';

/** 构造一个详情页状态卡，验证节点联合类型的写法。 */
export function statusCard(label: string, value: string): UiNode {
    return { type: 'statusCard', label, value, tone: 'ok', detail: '' };
}

/**
 * 验证绑定写法能被类型系统接受，且静态值与绑定可以共用同一套节点类型。
 *
 * 会话界面就是这么拼的：消息列表与输入框各是一个普通节点，
 * 数据由 `binding` 指向宿主数据源。
 */
export function sessionView(): UiNode {
    return {
        type: 'column',
        children: [
            {
                type: 'list',
                maxHeightDp: 320,
                emptyHint: '还没有消息',
                items: { binding: 'session:main:messages' },
            },
            {
                type: 'input',
                actionId: 'send',
                placeholder: '说点什么',
                submitTo: 'session:main',
                enabled: { binding: 'session:main:inputEnabled' },
            },
            { type: 'text', emphasis: 'caption', body: { binding: 'session:main:status' } },
        ],
    };
}

/** 验证 ctx 的能力都在声明里，且参数顺序与宿主一致。 */
export async function probeCtx(date: string): Promise<void> {
    const session = ctx.http.session('default', true);
    ctx.http.setUserAgent(session, 'LifeBoard/1.0');
    ctx.http.setHeader(session, 'Referer', 'https://example.com/');
    ctx.http.setHeader(session, 'Referer', null);
    ctx.http.setFollowRedirects(session, true);

    const res = await ctx.http.get('https://example.com/path', {
        headers: { Accept: 'application/json' },
        timeoutMs: 60_000,
        retry: true,
        followRedirects: false,
    });

    ctx.log.d('Probe', `status=${res.status} ok=${res.ok}`);
    // 二进制响应时 body 为空串，内容只在 bodyBase64
    if (res.bodyBase64) ctx.codec.base64Decode(res.bodyBase64);

    ctx.http.cookieSet(session, 'deviceId', 'ABC', 'example.com', '/');
    const token = ctx.http.cookieGet(session, 'token', 'sts.example.com');
    if (token) ctx.log.i('Probe', token.length.toString());

    try {
        await ctx.http.postForm(
            'https://example.com/login',
            [['user', 'a'], ['tag', 'b']],
            { retry: false },
            session,
        );
    } catch (e) {
        // lastError() 是稳定分类，不要用异常 message 做字符串匹配
        const kind = ctx.http.lastError();
        if (kind === 'timeout') ctx.log.w('Probe', '超时');
        else ctx.log.e('Probe', String(e));
    }

    ctx.credentials.put('account', 'demo');
    const account = ctx.credentials.get('account');
    if (account === null) ctx.credentials.remove('account');

    ctx.files.write('device_id.txt', 'ABC');
    const saved = ctx.files.read('device_id.txt');
    if (saved) ctx.log.d('Probe', saved);
    const bin = ctx.files.readBase64('a.bin');
    if (bin) ctx.files.writeBase64('b.bin', bin);

    // crypto 只收字节数组，字符串必须自己转
    const bytes = utf8Bytes('abc');
    ctx.log.d('Probe', ctx.crypto.md5Hex(bytes));
    ctx.log.d('Probe', ctx.crypto.sha256Hex(bytes));
    ctx.log.d('Probe', ctx.codec.base64Encode(bytes));

    const samples = ctx.recentMetrics('STEPS', 7);
    samples.forEach((s) => ctx.log.d('Probe', `${s.date}=${Number(s.value)}`));

    ctx.reportRawRecords('STEPS', [{ timestamp: Date.now(), value: 100, detail: 'x' }], date);
    ctx.reportMetricQueried('STEPS', date);
    ctx.log.d('Probe', ctx.pluginId());
}

/** QuickJS 没有 TextEncoder，UTF-8 编码要手写。 */
export function utf8Bytes(str: string): number[] {
    const out: number[] = [];
    for (let i = 0; i < str.length; i++) {
        let code = str.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
            const low = str.charCodeAt(i + 1);
            if (low >= 0xdc00 && low <= 0xdfff) {
                code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                i++;
            }
        }
        if (code < 0x80) out.push(code);
        else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
    return out;
}
