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

// 具名类型从契约声明文件导入（它是模块）；裸 `ctx` 与其余契约类型由
// `../index.d.ts` 的全局声明提供——那份文件刻意不是模块，见其说明。
import type { ChatMessage, PickerCandidate, UiNode } from '../plugin-contract.js';

/** 构造一个详情页状态卡，验证节点联合类型的写法。 */
export function statusCard(label: string, value: string): UiNode {
    return { type: 'statusCard', label, value, tone: 'ok', detail: '' };
}

/**
 * 验证绑定写法能被类型系统接受，且静态值与绑定可以共用同一套节点类型。
 *
 * 会话界面就是这么拼的：对话流与选择器各是一个普通节点，
 * 数据由 `binding` 指向宿主数据源。
 */
export function sessionView(): UiNode {
    return {
        type: 'column',
        children: [
            {
                type: 'chat',
                selfName: '我',
                peerName: '助手',
                maxHeightDp: 320,
                emptyHint: '还没有消息',
                messages: { binding: 'session:main:messages' },
            },
            {
                type: 'input',
                actionId: 'send',
                placeholder: '说点什么',
                submitTo: 'session:main',
                enabled: { binding: 'session:main:inputEnabled' },
            },
            {
                type: 'text',
                emphasis: 'caption',
                // 嵌在对话下方的状态说明不套卡片，避免两层边框
                card: false,
                body: { binding: 'session:main:status' },
            },
        ],
    };
}

/**
 * 验证清单选择器的写法：候选来自宿主数据源，选中态由插件回传。
 *
 * 这里同时钉住三件事：
 * 1. `sessionList::items` 是**中间段留空**的三段式（清单源没有实例 key）；
 * 2. `selectedIds` 让树重建后选中态不丢；
 * 3. 单选回传候选 id、多选回传 JSON 数组。
 */
export function sessionPickerView(currentSession: string, selected: string[]): UiNode {
    return {
        type: 'column',
        card: false,
        children: [
            {
                type: 'picker',
                actionId: 'switchSession',
                title: '选择会话',
                mode: 'modal',
                selectedIds: selected,
                emptyHint: '还没有历史会话',
                candidates: { binding: 'sessionList::items' },
            },
            {
                type: 'text',
                emphasis: 'caption',
                body: `当前会话：${currentSession}`,
            },
        ],
    };
}

/** 验证对话节点的静态历史记录写法（含轮次与流式标记）。 */
export function staticChatView(): UiNode {
    return {
        type: 'chat',
        showTime: true,
        messages: [
            { role: 'user', text: '你好', id: 'm1', turnId: 't1', timeLabel: '10:00' },
            { role: 'assistant', text: '在的', id: 'm2', turnId: 't1' },
            { role: 'assistant', text: '你问的是……', id: 'm3', turnId: 't1', pending: true },
            { role: 'system', text: '会话已恢复' },
        ],
    };
}

/** 验证列表整行点击与受控选区的新字段能被类型系统接受。 */
export function listAndSelectView(selectedValues: string[]): UiNode {
    return {
        type: 'column',
        children: [
            {
                type: 'list',
                itemActionId: 'openRecord',
                itemPayloads: ['2026-11-26#1', '2026-11-26#2'],
                items: [
                    [{ type: 'text', card: false, body: '第一条' }],
                    [{ type: 'text', card: false, body: '第二条' }],
                ],
            },
            {
                type: 'select',
                actionId: 'pickTag',
                label: '标签',
                multiple: true,
                submitLabel: '确定',
                selectedValues,
                options: [
                    { value: 'a', label: 'A', payload: '{"id":1}' },
                    { value: 'b', label: 'B' },
                ],
            },
        ],
    };
}

/**
 * 反向探针：v5 改动的字段写错时必须报错。
 *
 * 这些都不是「用法偏好」，而是**契约本身已经变了**：
 * `code.language` 被删除、`picker` 必须有 `actionId`、候选必须有 `id`。
 * 没有这些断言，插件作者会照着旧文档写出一个编译通过、运行时静默失效的界面。
 *
 * ## 为什么不用 `@ts-expect-error` 挂在对象字面量上
 *
 * 对象字面量赋给联合类型时，TS 不做多余属性检查（`language` 这类字段
 * 会静默通过）。所以这里用**函数签名**做反证：真正能被赋值进契约的类型
 * 才能通过，多带字段就不行。这比依赖字面量的行为更可靠。
 */
export function mustNotCompileNodes(): void {
    // @ts-expect-error v5 起 code 不再有 language（宿主不做语法高亮）
    const badCode: Extract<UiNode, { type: 'code' }> = { type: 'code', code: 'x', language: 'kotlin' };
    void badCode;

    // @ts-expect-error picker 的 actionId 必填：没有它点了也没人认
    const badPicker: Extract<UiNode, { type: 'picker' }> = { type: 'picker', candidates: [] };
    void badPicker;

    // @ts-expect-error 候选必须有 id
    const badCandidate: PickerCandidate = { label: '缺少 id' };
    void badCandidate;

    // @ts-expect-error 候选只允许语义字段，不能嵌任意节点
    const badCandidateNode: PickerCandidate = { id: 's1', label: '会话', node: { type: 'text' } };
    void badCandidateNode;

    // @ts-expect-error mode 只有 modal / inline
    const badMode: Extract<UiNode, { type: 'picker' }> = { type: 'picker', actionId: 'x', mode: 'popup' };
    void badMode;

    // @ts-expect-error 消息 role 只有 user / assistant / system
    const badMessage: ChatMessage = { role: 'robot', text: '?' };
    void badMessage;
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
