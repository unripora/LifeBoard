/**
 * DSH 会话层回归：在假宿主里驱动 **真实打包产物** `dsh/main.js`。
 *
 * 覆盖本轮修复的四个行为：
 * 1. 两个内容相同的独立 delta 必须各发一次（旧实现按「累计值」前缀比较，会吞掉一个）
 * 2. 本机提交产生的 user 回声被抑制，但同文本的电脑端新消息仍然显示
 * 3. 连接时已经 running 的会话，不能因为「连上了」就把输入解锁
 * 4. 必须等到远端 `turn/end` 才解锁输入
 *
 * 用法：node _mockhost/session.mjs [bundle 路径]
 * 非 0 退出码即失败。这不是真机验证：不模拟真实网络、TLS 与 QuickJS。
 */

import { createMockCtx, loadPlugin } from './index.mjs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const target = process.env.DSH_BUNDLE || process.argv[2] || 'dsh/main.js';

const failures = [];
function check(name, ok, detail) {
    console.log(`  ${ok ? '✓' : '✗'} ${name}${!ok && detail ? '\n      ' + detail : ''}`);
    if (!ok) failures.push(name);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 插件推给宿主的所有会话 UI 事件 */
function sessionEvents(mock) {
    return mock.snapshot().emitted.session.map((raw) => {
        try {
            return JSON.parse(raw);
        } catch {
            return { type: 'unparsable' };
        }
    });
}

/**
 * 极简 mux：真实协议是 `{type:'item', streamId, value}` 信封，
 * 插件 open 一条流后，网关在该 streamId 上逐条推 `value`。
 *
 * 这里把「逻辑帧」包装成对应 streamId 的 item 再交付；
 * `frames` 是数组引用，测试可以在订阅建立之后再往里追加。
 */
function installMux(ctx, frames) {
    let connectionId = 0;
    let streamId = '';
    ctx.ws.connect = async () => `conn-${++connectionId}`;
    ctx.ws.send = (_conn, text) => {
        const frame = JSON.parse(text);
        if (frame.type === 'open') streamId = String(frame.streamId ?? '');
    };
    ctx.ws.recv = async () => {
        // 帧耗尽后轮询等待：测试会在订阅建立之后再追加帧（真实网关是主动推）
        for (let i = 0; i < 400; i++) {
            const next = frames.shift();
            if (next !== undefined) {
                return {
                    type: 'text',
                    data: JSON.stringify({ type: 'item', streamId, value: next }),
                };
            }
            await new Promise((r) => setTimeout(r, 5));
        }
        return { type: 'close' };
    };
    ctx.ws.close = () => {};
}

async function main() {
    /** 一元 RPC 的服务端：list 返回 running 会话，prompt 回显 rpcId */
    let promptRpcId = '';
    const mock = createMockCtx({
        pluginId: 'dsh.regression',
        credentials: {
            'gateway-pairing': JSON.stringify({
                gateway: 'https://127.0.0.1:9100',
                label: '回归',
                // instanceId 就是 CA 指纹（sha256 hex，全长 64 位），
                // 假宿主不校验证书内容，只要长度满足「已配对」前提
                instanceId: 'a'.repeat(64),
                deviceId: 'dev-1',
                deviceToken: 'device-token',
                deviceExpiresAt: Date.now() + 3_600_000,
                sessionToken: 'session-token',
                csrfToken: 'csrf',
                sessionExpiresAt: Date.now() + 3_600_000,
            }),
        },
        http: ({ url, body }) => {
            const request = JSON.parse(body || '{}');
            // 一元 RPC 的实际路径是 `<gateway>/api/<endpoint>`
            if (url.includes('/session/list')) {
                return {
                    status: 200,
                    body: JSON.stringify({
                        type: 'server-response',
                        rpcId: request.rpcId,
                        result: {
                            ok: true,
                            value: {
                                items: [
                                    { sessionId: 's1', running: true, blank: false, agentAvailable: true, updatedAt: 1 },
                                ],
                            },
                        },
                    }),
                };
            }
            if (url.includes('/session/prompt')) {
                // 服务端把 requestId 写进 user/message.source.rpcId，
                // 插件据此认出「这条回声是我自己发的」
                promptRpcId = request?.payload?.args?.request?.requestId ?? '';
                return {
                    status: 200,
                    body: JSON.stringify({
                        type: 'server-response',
                        rpcId: request.rpcId,
                        result: { ok: true, value: {} },
                    }),
                };
            }
            if (url.includes('/device/')) {
                // 续期相关的其它端点：假宿主不模拟，返回空成功
                return {
                    status: 200,
                    body: JSON.stringify({ type: 'server-response', rpcId: request.rpcId, result: { ok: true, value: {} } }),
                };
            }
            return { status: 404, body: '{}' };
        },
    });

    const mod = await loadPlugin(pathToFileURL(resolve(target)).href, mock);
    // follow 帧在 prompt 之后才生成：先提交拿到 requestId，再模拟远端回帧。
    // 数组是引用，`installMux` 的 recv 会持续读它，后续 push 的帧也能交付。
    const followFrames = [
        // 连接即快照：会话在跑，但没有进行中的 attempt
        { type: 'snapshot', header: { id: 's1' }, records: [] },
    ];
    installMux(globalThis.ctx, followFrames);

    console.log('DSH 会话层回归：');
    const started = await mod.sessionStart({ sessionKey: 'dsh-s1' });
    check('sessionStart 成功', started === true);

    const afterConnect = sessionEvents(mock).filter((e) => e.type === 'status');
    check(
        '连接后不把 running 会话标成可输入',
        !afterConnect.some((e) => e.inputEnabled === true && String(e.text).startsWith('已连接')),
        JSON.stringify(afterConnect),
    );

    await mod.sessionEvent({ type: 'input', payload: '在吗' });
    check('提交拿到了服务端关联的 requestId', promptRpcId.length > 0, `requestId=${promptRpcId}`);
    check(
        '提交不会额外 emit 本机用户消息',
        !sessionEvents(mock).some((e) => e.type === 'message' && e.role === 'user'),
    );

    // 远端回帧：回声 → 电脑端同文本消息 → 两个相同 delta → turn 结束
    followFrames.push(
        {
            type: 'event', streamId: 's1',
            event: { type: 'user/message', data: { role: 'user', text: '在吗', source: { rpcId: promptRpcId } } },
        },
        {
            type: 'event', streamId: 's1',
            event: { type: 'user/message', data: { role: 'user', text: '在吗', source: { rpcId: 'other-rpc' } } },
        },
        { type: 'assistant-stream', streamId: 's1', frame: { type: 'start', attemptId: 'a1', revision: 10 } },
        {
            type: 'assistant-stream', streamId: 's1',
            frame: { type: 'chunk', attemptId: 'a1', revision: 11, index: 0, chunk: { type: 'text-delta', text: '哈' } },
        },
        {
            type: 'assistant-stream', streamId: 's1',
            frame: { type: 'chunk', attemptId: 'a1', revision: 12, index: 1, chunk: { type: 'text-delta', text: '哈' } },
        },
        { type: 'event', streamId: 's1', event: { type: 'turn/end', data: { reason: { kind: 'complete' } } } },
    );
    await sleep(80);

    const deltas = sessionEvents(mock).filter((e) => e.type === 'delta').map((e) => e.text);
    check(
        '两个相同内容的独立 delta 各发一次',
        deltas.filter((t) => t === '哈').length === 2,
        JSON.stringify(deltas),
    );

    const userMessages = sessionEvents(mock).filter((e) => e.type === 'message' && e.role === 'user');
    check(
        '同文本的电脑端新消息仍显示，本机回声被抑制',
        userMessages.filter((m) => m.text === '在吗').length === 1,
        JSON.stringify(userMessages),
    );

    const statuses = sessionEvents(mock).filter((e) => e.type === 'status');
    check(
        'turn/end 之后才解锁输入',
        statuses.at(-1)?.inputEnabled === true,
        JSON.stringify(statuses.at(-1)),
    );

    mod.sessionStop();
}

await main();
if (failures.length > 0) {
    console.error(`\n失败 ${failures.length} 项`);
    process.exit(1);
}
console.log('\n全部通过（假宿主，不等于真机）');
