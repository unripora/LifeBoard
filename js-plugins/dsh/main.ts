// DSH Remote 插件入口。
//
// ## 当前进度
//
// M6.0：把上游密码栈（Noise IK + X25519 + ChaCha20-Poly1305）打成单文件 ESM，
// 并在真引擎里对 `fixtures/crypto/v1/noise-ik.json` 逐字节自检。
// 之后的 REST 登录（6.2）、Control 通道（6.3）、RPC（6.4）会逐步补到本文件。
//
// ## 为什么先做自检而不是先连服务器
//
// 握手只要有一个字节不对，表现就是「WS 连上了、但一条消息也解不开」，
// 而此时牵扯的是登录、网络、协议三层，很难判断问题出在密码实现。
// 先用官方向量钉死密码层，后面每一步出错都能立刻排除这一类原因。

/// <reference path="../types/index.d.ts" />

import { fromBase64Url, identityFingerprint, toBase64Url } from './keys.js';
import { NoiseIkSession } from './noise.js';
import { deriveWsUrl, needsRefresh, normalizeServerUrl, readHostDevices } from './api.js';
import { ControlChannel, ControlStateError } from './controlChannel.js';
import {
    createRpcRequest,
    decodeMessage,
    encodeMessage,
    readInbound,
    RemoteCallError,
    RpcPending,
    unwrapRemoteResult,
} from './messages.js';
import {
    createSessionCreateRequest,
    createSessionListRequest,
    createSessionPromptRequest,
    REMOTE_CALL_METHOD,
    readSessionCreateResult,
    readSessionList,
    SESSION_PROMPT_ENDPOINT,
} from './sessions.js';
import {
    AssistantStreamAggregator,
    createSessionFollowRequest,
    createStreamCloseRequest,
    EVENT_FRAME,
    EVENT_STREAM_CLOSED,
    MAX_SESSION_FOLLOW_MESSAGES,
    parseStreamEvent,
    SESSION_FOLLOW_ENDPOINT,
    STREAM_CLOSE_METHOD,
    STREAM_OPEN_METHOD,
} from './stream.js';
import {
    createApprovalResultRequest,
    createEventsStreamRequest,
    createQuestionResultRequest,
    createRejectedResultRequest,
    EVENTS_ENDPOINT,
    EVENTS_RESULT_ENDPOINT,
    parseEventsFrame,
} from './interactions.js';
import {
    ConnectionEpoch,
    DEFAULT_RECONNECT_POLICY,
    nextReconnectDelay,
    planRecovery,
    type RecoveryAction,
} from './recovery.js';
import {
    createWorkspaceFollowRequest,
    readWorkspaceBaseline,
    readWorkspaceList,
    WORKSPACE_FOLLOW_ENDPOINT,
} from './workspaces.js';
import { ConversationController } from './conversation.js';
// 契约出口：宿主只认入口模块（`main.js`）的具名导出，因此这里必须再导出一次
export {
    authState,
    detailUi,
    fetch,
    health,
    login,
    logout,
    onAction,
    sessionEvent,
    sessionStart,
    sessionStop,
} from './contract.js';
import {
    createFrame,
    decodeFrame,
    encodeFrame,
    ProtocolError,
    readHelloAck,
    readSecureHandshake,
} from './protocol.js';

interface NoiseVectorFile {
    fixtureFormat: string;
    fixtureFormatVersion: number;
    suite: string;
    encoding: string;
    vectors: NoiseVector[];
}

interface NoiseVector {
    name: string;
    staticKeys: {
        initiator: { privateKey: string; publicKey: string };
        responder: { privateKey: string; publicKey: string };
    };
    ephemeralPrivateKeys: { initiator: string; responder: string };
    prologue: { connectionId: string; hostDeviceId: string; clientDeviceId: string; bytes: string };
    handshake: {
        message1: { sender: string; payload: string; message: string };
        message2: { sender: string; payload: string; message: string };
    };
    transport: Array<{
        sender: 'initiator' | 'responder';
        plaintext: string;
        ciphertext: string;
    }>;
}

/**
 * 按顺序吐出预置字节的随机源。
 *
 * Noise 的临时密钥来自 RNG，只有把它钉死，握手才能与向量逐字节比较；
 * 真实运行时用宿主 `ctx.crypto.randomBytes`（`noise.ts` 末尾的默认实现）。
 */
function fixedRng(hex: string): (length: number) => Uint8Array {
    const bytes = fromBase64Url(hex);
    let offset = 0;
    return (length: number) => {
        const out = bytes.slice(offset, offset + length);
        offset += length;
        if (out.byteLength !== length) {
            throw new Error(`固定随机源已耗尽：需要 ${length} 字节，剩余 ${out.byteLength}`);
        }
        return out;
    };
}

function sameBytes(actual: Uint8Array, expected: Uint8Array): boolean {
    if (actual.byteLength !== expected.byteLength) return false;
    for (let i = 0; i < actual.byteLength; i += 1) {
        if (actual[i] !== expected[i]) return false;
    }
    return true;
}

/**
 * 用官方向量自检密码层。
 *
 * @param vectorsJson `fixtures/crypto/v1/noise-ik.json` 的原文
 * @returns `{ ok, checks, failures }`：`checks` 是逐条通过项，`failures` 是逐条不符项
 *
 * 返回对象而不是抛错：自检的价值在于「哪一条不对」，
 * 直接抛出只能得到一个异常，看不出是 message1 还是 transport[2] 出问题。
 */
export function selfTest(vectorsJson: string): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    let file: NoiseVectorFile;
    try {
        file = JSON.parse(vectorsJson) as NoiseVectorFile;
    } catch (e) {
        return { ok: false, checks, failures: [`向量文件不是合法 JSON：${String(e)}`] };
    }

    if (file.fixtureFormat !== 'dsh-remote.noise-vectors') {
        failures.push(`fixtureFormat 不符：${file.fixtureFormat}`);
    }

    file.vectors.forEach((vector) => {
        const prologue = fromBase64Url(vector.prologue.bytes);
        const initiator = new NoiseIkSession({
            role: 'initiator',
            localPrivateKey: vector.staticKeys.initiator.privateKey,
            localPublicKey: vector.staticKeys.initiator.publicKey,
            remotePublicKey: vector.staticKeys.responder.publicKey,
            prologue,
            random: fixedRng(vector.ephemeralPrivateKeys.initiator),
        });
        const responder = new NoiseIkSession({
            role: 'responder',
            localPrivateKey: vector.staticKeys.responder.privateKey,
            localPublicKey: vector.staticKeys.responder.publicKey,
            remotePublicKey: vector.staticKeys.initiator.publicKey,
            prologue,
            random: fixedRng(vector.ephemeralPrivateKeys.responder),
        });

        // 1. 握手消息 1（initiator → responder）
        const message1 = initiator.writeHandshake(Uint8Array.from(fromBase64Url(vector.handshake.message1.payload)));
        if (sameBytes(message1, fromBase64Url(vector.handshake.message1.message))) {
            checks.push(`${vector.name}: message1 与向量一致`);
        } else {
            failures.push(`${vector.name}: message1 不一致（实际 ${toBase64Url(message1)}）`);
        }

        // 2. responder 读消息 1，产出消息 2
        const readPayload1 = responder.readHandshake(message1);
        if (sameBytes(readPayload1, fromBase64Url(vector.handshake.message1.payload))) {
            checks.push(`${vector.name}: responder 解出的 payload1 正确`);
        } else {
            failures.push(`${vector.name}: responder 解出的 payload1 不符`);
        }
        const message2 = responder.writeHandshake(Uint8Array.from(fromBase64Url(vector.handshake.message2.payload)));
        if (sameBytes(message2, fromBase64Url(vector.handshake.message2.message))) {
            checks.push(`${vector.name}: message2 与向量一致`);
        } else {
            failures.push(`${vector.name}: message2 不一致（实际 ${toBase64Url(message2)}）`);
        }

        // 3. initiator 读消息 2，握手完成
        const readPayload2 = initiator.readHandshake(message2);
        if (sameBytes(readPayload2, fromBase64Url(vector.handshake.message2.payload))) {
            checks.push(`${vector.name}: initiator 解出的 payload2 正确`);
        } else {
            failures.push(`${vector.name}: initiator 解出的 payload2 不符`);
        }
        if (initiator.complete && responder.complete) {
            checks.push(`${vector.name}: 双方握手完成`);
        } else {
            failures.push(`${vector.name}: 握手未完成`);
        }

        // 4. 传输帧：加密端必须与向量一致，解密端必须还原出原文
        vector.transport.forEach((frame, index) => {
            const from = frame.sender === 'initiator' ? initiator : responder;
            const to = frame.sender === 'initiator' ? responder : initiator;
            const plaintext = fromBase64Url(frame.plaintext);
            const ciphertext = from.encrypt(plaintext);
            if (sameBytes(ciphertext, fromBase64Url(frame.ciphertext))) {
                checks.push(`${vector.name}: transport[${index}] 密文一致`);
            } else {
                failures.push(`${vector.name}: transport[${index}] 密文不一致`);
            }
            const decoded = to.decrypt(ciphertext);
            if (sameBytes(decoded, plaintext)) {
                checks.push(`${vector.name}: transport[${index}] 可被对端还原`);
            } else {
                failures.push(`${vector.name}: transport[${index}] 对端还原失败`);
            }
        });

        initiator.destroy();
        responder.destroy();
    });

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 指纹自检：同一公钥两次调用必须得到同一个 12 位大写十六进制串。
 *
 * 这条不需要官方向量——它验证的是「截断与编码没写反」，
 * 而这类低级错误在真机上一眼看不出来（指纹照样是 12 位，只是永远对不上）。
 */
export function fingerprintSelfTest(publicKey: string): { ok: boolean; fingerprint: string } {
    const first = identityFingerprint(publicKey);
    const second = identityFingerprint(publicKey);
    return {
        ok: first === second && /^[0-9A-F]{12}$/.test(first),
        fingerprint: first,
    };
}

/**
 * 协议层自检：帧封装、入站校验、地址规范化。
 *
 * 这些函数要么纯计算、要么只依赖宿主随机源，因此可以在没有 Server 的环境里钉住。
 * 等 6.2 拿到测试账号后，这里再加「真实响应解析」的部分。
 */
export function protocolSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    // 1. 构造 → 序列化 → 解析，字段原样保留
    const frame = createFrame('ping', { nonce: 'abc' });
    const decoded = decodeFrame(encodeFrame(frame));
    expect('帧往返后 type 一致', decoded.type === 'ping', decoded.type);
    expect('帧往返后 payload 一致', (decoded.payload as { nonce: string }).nonce === 'abc');

    // 2. 入站校验：坏输入必须被挡在业务逻辑之前
    const rejects = (name: string, text: string, code: string) => {
        try {
            decodeFrame(text);
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };
    rejects('非 JSON 被拒绝', 'not json', 'NOT_JSON');
    rejects('版本不符被拒绝', '{"v":2,"id":"a","type":"ping","timestamp":1,"payload":{}}', 'BAD_FRAME');
    rejects('未知类型被拒绝', '{"v":1,"id":"a","type":"nope","timestamp":1,"payload":{}}', 'UNKNOWN_TYPE');
    rejects('payload 非对象被拒绝', '{"v":1,"id":"a","type":"ping","timestamp":1,"payload":[]}', 'BAD_FRAME');

    // 3. hello.ack：字段齐全时解析成功，且上限取双方较小值
    try {
        const ack = readHelloAck({
            protocol: 1,
            serverVersion: '0.1.0',
            connectionSessionId: 's1',
            heartbeatIntervalMs: 25000,
            maxControlFrameBytes: 999999,
            maxRelayFrameBytes: 1048576,
        });
        expect('hello.ack 心跳被读出', ack.heartbeatIntervalMs === 25000);
        expect('hello.ack 单帧上限取较小值', ack.maxControlFrameBytes === 64 * 1024, String(ack.maxControlFrameBytes));
    } catch (e) {
        expect('hello.ack 解析', false, String(e));
    }

    // 4. secure.handshake：step 只能是 1 或 2
    try {
        readSecureHandshake({ connectionId: 'c', targetDeviceId: 'd', step: 3, data: 'AA' });
        expect('secure.handshake 非法 step 被拒绝', false, '本应抛错');
    } catch (e) {
        expect('secure.handshake 非法 step 被拒绝', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    // 5. Server 地址规范化
    const urlCases: Array<[string, string, boolean]> = [
        ['https://dsh.r2049.cn', 'https://dsh.r2049.cn', true],
        [' https://dsh.r2049.cn/ ', 'https://dsh.r2049.cn', true],
        ['http://dsh.r2049.cn', '', false],
        ['https://dsh.r2049.cn/app', '', false],
        ['https://user:pw@dsh.r2049.cn', '', false],
        ['ftp://dsh.r2049.cn', '', false],
    ];
    urlCases.forEach(([input, expected, shouldPass]) => {
        try {
            const actual = normalizeServerUrl(input);
            expect(`地址规范化 ${input}`, shouldPass && actual === expected, `得到 ${actual}`);
        } catch (e) {
            expect(`地址规范化 ${input}`, !shouldPass, `本应接受：${String(e)}`);
        }
    });
    expect('地址派生 wss', deriveWsUrl('https://dsh.r2049.cn') === 'wss://dsh.r2049.cn/ws/v1/connect');

    // 6. 刷新窗口
    expect('即将过期需要刷新', needsRefresh(1000, 900, 150));
    expect('剩余充足不需刷新', !needsRefresh(10_000, 900, 150));
    expect('无过期时间按需刷新', needsRefresh(0, 900, 150));

    return { ok: failures.length === 0, checks, failures };
}

/**
 * Control 通道状态机自检。
 *
 * 全部同步：不碰 `ctx.ws`，只喂帧文本给 `ControlChannel.handle`。
 * 这正是把状态机与拉取循环拆开的目的——连接行为可以离线钉死，
 * 剩下的拉取循环只有十几行胶水，联调时一眼能看完。
 */
export function controlSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const frameText = (type: string, payload: unknown) =>
        JSON.stringify({ v: 1, id: `t_${type}`, type, timestamp: Date.now(), payload });

    const ackPayload = {
        protocol: 1,
        serverVersion: '0.1.0',
        connectionSessionId: 's1',
        heartbeatIntervalMs: 25000,
        maxControlFrameBytes: 65536,
        maxRelayFrameBytes: 1048576,
        webrtcEnabled: false,
    };

    const sees: string[] = [];
    const handlers = {
        onConnectIncoming: (f: { connectionId: string }) => sees.push(`incoming:${f.connectionId}`),
        onSecureHandshake: (f: { step: number }) => sees.push(`handshake:${f.step}`),
        onUnsupported: (t: string) => sees.push(`unsupported:${t}`),
    };

    // 1. hello 内容正确
    const greeting = new ControlChannel(handlers);
    const hello = JSON.parse(greeting.createHello({ deviceId: 'dev-1', accessToken: 'tok', role: 'client' }));
    expect('hello 的 role 是 client', hello.payload.role === 'client');
    expect('hello 声明协议版本 1', JSON.stringify(hello.payload.protocols) === '[1]');
    expect('hello 的帧类型正确', hello.type === 'hello');

    // 2. 首帧不是 hello.ack 必须拒绝（含服务端直接拒绝）
    const rejectsFirst = (name: string, text: string) => {
        const c = new ControlChannel(handlers);
        try {
            c.handle(text);
            expect(name, false, '本应抛错');
        } catch (e) {
            expect(name, e instanceof ControlStateError && e.code === 'BAD_FIRST_FRAME', String(e));
        }
    };
    rejectsFirst('首帧为 ping 被拒绝', frameText('ping', { nonce: 'n' }));
    rejectsFirst('首帧为 error 被拒绝', frameText('error', { code: 'AUTH_INVALID', message: 'token 无效' }));

    // 3. 正常握手
    const channel = new ControlChannel(handlers);
    const ready = channel.handle(frameText('hello.ack', ackPayload));
    expect('hello.ack 被解析为 ack', ready.ack?.connectionSessionId === 's1');
    expect('握手后状态为 ready', channel.ready);

    // 4. 心跳：pong 原样带回 nonce
    const pong = channel.handle(frameText('ping', { nonce: 'abc123' }));
    expect('ping 产出一帧 pong', pong.sends.length === 1, String(pong.sends.length));
    const pongFrame = JSON.parse(pong.sends[0] ?? '{}');
    expect('pong 的 nonce 原样带回', pongFrame.payload?.nonce === 'abc123');
    expect('pong 的版本为 1', pongFrame.v === 1);

    // 5. 各类型分发
    channel.handle(frameText('connect.incoming', {
        connectionId: 'conn-1',
        clientDeviceId: 'cli-1',
        clientIdentityKey: 'key',
        authorization: 'account',
    }));
    expect('connect.incoming 被分发', sees.includes('incoming:conn-1'), sees.join(','));

    channel.handle(frameText('secure.handshake', {
        connectionId: 'conn-1',
        targetDeviceId: 'dev-1',
        step: 1,
        data: 'AA',
    }));
    expect('secure.handshake 被分发', sees.includes('handshake:1'), sees.join(','));

    // 6. 不支持的类型只记录，不断连
    channel.handle(frameText('signal.offer', { sdp: 'x' }));
    channel.handle(frameText('future.thing', {}));
    expect('WebRTC 信令被记为不支持', sees.includes('unsupported:signal.offer'), sees.join(','));
    expect('完全未知的类型被忽略', sees.includes('unsupported:<unknown>'), sees.join(','));
    expect('遇到不支持类型后仍 ready', channel.ready);

    // 7. 非法 payload 必须抛，不能被当成正常帧
    try {
        channel.handle(frameText('connect.incoming', {
            connectionId: 'conn-2',
            clientDeviceId: 'cli-2',
            clientIdentityKey: 'key',
            authorization: 'code',
        }));
        expect('非 account 授权方式被拒绝', false, '本应抛错');
    } catch (e) {
        expect('非 account 授权方式被拒绝', e instanceof ProtocolError && e.code === 'UNSUPPORTED');
    }

    try {
        channel.handle(frameText('secure.handshake', {
            connectionId: 'conn-2',
            targetDeviceId: 'dev-1',
            step: 3,
            data: 'AA',
        }));
        expect('非法 step 被拒绝', false, '本应抛错');
    } catch (e) {
        expect('非法 step 被拒绝', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    // 8. 关闭后写帧必须失败，避免往死连接里塞数据
    channel.close();
    try {
        channel.send('connect.request', { hostDeviceId: 'h' });
        expect('关闭后发帧被拒绝', false, '本应抛错');
    } catch (e) {
        expect('关闭后发帧被拒绝', e instanceof ControlStateError && e.code === 'CLOSED');
    }

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 业务消息（RPC / Event）自检。
 *
 * 与控制帧自检同构：纯同步、不碰 `ctx.ws`、不需要服务端。
 * 钉住的是「信封字段校验、四种消息分类、挂起请求表」三件事，
 * 这些一旦错了，联调时会表现成「响应到了但回不到调用方」，极难定位。
 */
export function rpcSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const messageText = (type: string, payload: unknown) =>
        JSON.stringify({ v: 1, id: `m_${type}`, type, timestamp: Date.now(), payload });

    // 1. 构造请求：字段齐全，id 唯一
    const request = createRpcRequest('harness.remote.call', { endpoint: 'session/list', payload: {} });
    expect('请求类型为 rpc.request', request.type === 'rpc.request', request.type);
    expect('请求版本为 1', request.v === 1, String(request.v));
    expect('请求方法名保留', request.payload.method === 'harness.remote.call', request.payload.method);
    expect('请求 id 非空', request.id.length > 0);
    expect('两次请求 id 不同', createRpcRequest('a', {}).id !== createRpcRequest('a', {}).id);

    // 2. 编码 → 解码，字段原样保留
    const roundTrip = decodeMessage(encodeMessage(request));
    expect('往返后 id 一致', roundTrip.id === request.id);
    expect('往返后方法名一致',
        (roundTrip.payload as { method: string }).method === 'harness.remote.call');

    // 3. 入站分类：四种类型各走各的分支
    const response = readInbound(messageText('rpc.response', {
        requestId: request.id,
        result: { sessions: [] },
    }));
    expect('响应被识别为 response', response.kind === 'response', response.kind);
    expect('响应的 requestId 保留',
        response.kind === 'response' && response.requestId === request.id);

    const error = readInbound(messageText('rpc.error', {
        requestId: request.id,
        code: 'SESSION_NOT_FOUND',
        message: '会话不存在',
        retryable: true,
    }));
    expect('错误被识别为 error', error.kind === 'error', error.kind);
    expect('错误码原样保留', error.kind === 'error' && error.code === 'SESSION_NOT_FOUND');
    expect('retryable 被读出', error.kind === 'error' && error.retryable);

    const noRetry = readInbound(messageText('rpc.error', {
        requestId: request.id,
        code: 'AUTH_INVALID',
        message: '凭据无效',
    }));
    expect('缺省 retryable 为 false', noRetry.kind === 'error' && !noRetry.retryable);

    const event = readInbound(messageText('event', {
        event: 'harness.remote.frame',
        data: { streamId: 's1' },
        seq: 7,
        sessionId: 'sess-1',
    }));
    expect('事件被识别为 event', event.kind === 'event', event.kind);
    expect('事件名保留', event.kind === 'event' && event.event === 'harness.remote.frame');
    expect('事件序号保留', event.kind === 'event' && event.seq === 7);
    expect('事件会话 id 保留', event.kind === 'event' && event.sessionId === 'sess-1');

    const inboundRequest = readInbound(messageText('rpc.request', {
        method: 'workspace.upload.open',
        params: {},
    }));
    expect('反向请求被识别为 request', inboundRequest.kind === 'request', inboundRequest.kind);

    // 4. 坏输入必须被挡在业务逻辑之前
    const rejects = (name: string, text: string, code: string, limit?: number) => {
        try {
            readInbound(text, limit);
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };
    rejects('非 JSON 被拒绝', 'not json', 'NOT_JSON');
    rejects('版本不符被拒绝', '{"v":2,"id":"a","type":"event","timestamp":1,"payload":{}}', 'BAD_FRAME');
    rejects('未知消息类型被拒绝', '{"v":1,"id":"a","type":"rpc.ack","timestamp":1,"payload":{}}', 'UNKNOWN_TYPE');
    rejects('payload 非对象被拒绝', '{"v":1,"id":"a","type":"event","timestamp":1,"payload":[]}', 'BAD_FRAME');
    rejects('事件缺少名称被拒绝', messageText('event', { data: {} }), 'BAD_PAYLOAD');
    rejects('响应缺少 requestId 被拒绝', messageText('rpc.response', { result: {} }), 'BAD_PAYLOAD');

    // 5. 体积上限：出站超限必须在发出去之前抛
    try {
        encodeMessage(request, 16);
        expect('超限消息被拒绝', false, '本应抛错');
    } catch (e) {
        expect('超限消息被拒绝', e instanceof ProtocolError && e.code === 'BAD_FRAME');
    }

    // 6. 挂起请求表：登记、摘取、重复、清空
    const pending = new RpcPending();
    pending.track('id-1', 'harness.remote.call');
    pending.track('id-2', 'harness.remote.stream.open');
    expect('挂起表计数正确', pending.size === 2, String(pending.size));
    expect('登记后 has 为真', pending.has('id-1'));
    const settled = pending.settle('id-1');
    expect('摘取返回方法名', settled?.method === 'harness.remote.call', String(settled?.method));
    expect('摘取后从表里移除', !pending.has('id-1') && pending.size === 1, String(pending.size));
    expect('重复摘取返回 undefined', pending.settle('id-1') === undefined);
    expect('未知 id 摘取返回 undefined', pending.settle('nope') === undefined);

    try {
        pending.track('id-2', 'dup');
        expect('重复登记被拒绝', false, '本应抛错');
    } catch (e) {
        expect('重复登记被拒绝', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    expect('清空返回丢弃条数', pending.dropAll() === 1 && pending.size === 0);

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 会话列表与新建自检。
 *
 * 钉住两件最容易返工的事：
 * 1. `harness.remote.call` 的 params 层级（少嵌一层只会得到 Host 的「参数缺失」）；
 * 2. 返回值拆壳与逐条容错（Host 版本差异时列表不能整页白屏）。
 */
export function sessionsSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    // 1. 请求构造：方法名、endpoint、三层嵌套结构
    //    rpc.request 的 payload 是 { method, params }，params 再嵌 { endpoint, payload:{args} }
    const paramsOf = (message: { payload: unknown }) =>
        (message.payload as {
            params: { endpoint: string; payload: { args: Record<string, unknown> } };
        }).params;

    const listRequest = createSessionListRequest();
    expect('列表请求为 rpc.request', listRequest.type === 'rpc.request', listRequest.type);
    expect('列表请求走 harness.remote.call',
        (listRequest.payload as { method: string }).method === REMOTE_CALL_METHOD,
        (listRequest.payload as { method: string }).method);
    const listParams = paramsOf(listRequest);
    expect('列表请求 endpoint 为 session/list',
        listParams.endpoint === 'session/list', listParams.endpoint);
    expect('列表请求带 args._request',
        typeof listParams.payload.args._request === 'object');

    const byWorkspace = createSessionCreateRequest({ workspaceId: 'ws-1' });
    const workspaceParams = paramsOf(byWorkspace);
    const workspaceArgs = workspaceParams.payload.args.request as Record<string, unknown>;
    expect('新建请求 endpoint 为 session/create',
        workspaceParams.endpoint === 'session/create', workspaceParams.endpoint);
    expect('workspaceId 被保留', workspaceArgs.workspaceId === 'ws-1');
    expect('有 workspaceId 时不带 cwd', !('cwd' in workspaceArgs));

    const byCwd = createSessionCreateRequest({ cwd: '/tmp/project' });
    const cwdArgs = paramsOf(byCwd).payload.args.request as Record<string, unknown>;
    expect('cwd 被保留', cwdArgs.cwd === '/tmp/project');

    const byDefault = createSessionCreateRequest();
    const defaultArgs = paramsOf(byDefault).payload.args.request as Record<string, unknown>;
    expect('无目标时 request 为空对象',
        Object.keys(defaultArgs).length === 0, JSON.stringify(defaultArgs));

    // 2. 返回值拆壳：三种形态
    expect('ok:true 取出 value',
        (unwrapRemoteResult({ ok: true, value: { items: [] } }) as { items: unknown[] }).items.length === 0);
    expect('ok:true 无 value 时返回 undefined', unwrapRemoteResult({ ok: true }) === undefined);
    const raw = { sessionId: 's1' };
    expect('无 ok 字段时原样返回', unwrapRemoteResult(raw) === raw);

    try {
        unwrapRemoteResult({ ok: false, error: { code: 'SESSION_NOT_FOUND', message: '不存在', details: { id: 'x' } } });
        expect('ok:false 抛 RemoteCallError', false, '本应抛错');
    } catch (e) {
        expect('ok:false 抛 RemoteCallError', e instanceof RemoteCallError && e.code === 'SESSION_NOT_FOUND');
        expect('错误 details 保留',
            e instanceof RemoteCallError && (e.details as { id?: string }).id === 'x');
    }

    try {
        unwrapRemoteResult({ ok: false, error: { code: 'X' } });
        expect('残缺失败外壳抛 BAD_PAYLOAD', false, '本应抛错');
    } catch (e) {
        expect('残缺失败外壳抛 BAD_PAYLOAD', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    // 3. 列表解析：坏条目丢弃、标题回退、顺序保持
    const sessions = readSessionList({
        items: [
            { sessionId: 'a', updatedAt: 3, running: true, blank: false, title: '会话 A' },
            { sessionId: 'b', updatedAt: 2, running: false, blank: true },
            { sessionId: 'c', updatedAt: 1, running: false, blank: false, projections: { values: { title: '投影标题' } } },
            { sessionId: 'bad', updatedAt: 'yesterday', running: false, blank: false },
            { updatedAt: 0, running: false, blank: false },
        ],
    });
    expect('列表丢弃坏条目', sessions.length === 3, String(sessions.length));
    expect('列表顺序保持', sessions[0]?.sessionId === 'a' && sessions[2]?.sessionId === 'c');
    expect('running 被保留', sessions[0]?.running === true);
    expect('投影标题被回退采用', sessions[2]?.title === '投影标题', String(sessions[2]?.title));
    expect('非数组 items 视为空列表', readSessionList({ items: null }).length === 0);
    expect('非对象返回空列表', readSessionList(undefined).length === 0);

    // 4. 新建结果
    expect('新建返回 sessionId', readSessionCreateResult({ sessionId: 'new-1' }) === 'new-1');
    try {
        readSessionCreateResult({});
        expect('新建缺 sessionId 抛 BAD_PAYLOAD', false, '本应抛错');
    } catch (e) {
        expect('新建缺 sessionId 抛 BAD_PAYLOAD', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 发送 Prompt 与流式回复自检。
 *
 * 钉住三件最容易返工的事：
 * 1. `stream.open` 的 params 是扁平 `{streamId, endpoint, payload:{args}}`，
 *    而 `session/prompt` 走 `harness.remote.call` 的三层嵌套；
 * 2. 事件帧与终止帧的字段校验（尤其 `failed` 必须带出 failure）；
 * 3. 增量聚合只拼文本，忽略快照与工具调用（否则同一条回复会出现两次）。
 */
export function streamSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const expectThrows = (name: string, run: () => unknown, code: string) => {
        try {
            run();
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };

    // 1. Prompt 请求：harness.remote.call + session/prompt + 三层参数
    const prompt = createSessionPromptRequest('s-1', '你好', { requestId: 'req-1' });
    const promptPayload = prompt.payload as {
        method: string;
        params: {
            endpoint: string;
            payload: { args: { request: Record<string, unknown> } };
        };
    };
    expect('Prompt 走 harness.remote.call', promptPayload.method === REMOTE_CALL_METHOD);
    expect('Prompt endpoint 为 session/prompt',
        promptPayload.params.endpoint === SESSION_PROMPT_ENDPOINT, promptPayload.params.endpoint);
    const promptRequest = promptPayload.params.payload.args.request;
    expect('Prompt requestId 可复用', promptRequest.requestId === 'req-1');
    expect('Prompt sessionId 被保留', promptRequest.sessionId === 's-1');
    expect('Prompt 默认 queue 模式', promptRequest.mode === 'queue', String(promptRequest.mode));
    const content = promptRequest.content as Array<{ type: string; text: string }>;
    expect('Prompt 内容为单条文本',
        Array.isArray(content) && content.length === 1 && content[0].type === 'text' && content[0].text === '你好');
    expect('缺省不带 clientTimeZone', !('clientTimeZone' in promptRequest));
    const zoned = createSessionPromptRequest('s-1', 'x', { clientTimeZone: 'Asia/Shanghai' });
    const zonedRequest = (zoned.payload as {
        params: { payload: { args: { request: Record<string, unknown> } } };
    }).params.payload.args.request;
    expect('clientTimeZone 可选带上', zonedRequest.clientTimeZone === 'Asia/Shanghai');
    expectThrows('空文本 Prompt 被拒绝', () => createSessionPromptRequest('s-1', ''), 'BAD_PAYLOAD');

    // 2. session/follow：stream.open 参数是扁平的 streamId/endpoint/payload
    const follow = createSessionFollowRequest('stream-1', 's-1');
    const followPayload = follow.payload as {
        method: string;
        params: { streamId: string; endpoint: string; payload: { args: { request: Record<string, unknown> } } };
    };
    expect('跟随流走 stream.open', followPayload.method === STREAM_OPEN_METHOD, followPayload.method);
    expect('跟随流 streamId 被保留', followPayload.params.streamId === 'stream-1');
    expect('跟随流 endpoint 为 session/follow',
        followPayload.params.endpoint === SESSION_FOLLOW_ENDPOINT, followPayload.params.endpoint);
    const followRequest = followPayload.params.payload.args.request;
    const address = followRequest.address as { kind: string; sessionId: string };
    expect('会话地址为普通 session', address.kind === 'session' && address.sessionId === 's-1');
    expect('默认携带最大消息数', followRequest.maxMessages === MAX_SESSION_FOLLOW_MESSAGES,
        String(followRequest.maxMessages));
    const custom = createSessionFollowRequest('stream-2', 's-1', 5);
    const customRequest = (custom.payload as {
        params: { payload: { args: { request: Record<string, unknown> } } };
    }).params.payload.args.request;
    expect('maxMessages 可覆盖', customRequest.maxMessages === 5);
    expectThrows('空 sessionId 被拒绝', () => createSessionFollowRequest('stream-3', ''), 'BAD_PAYLOAD');
    expectThrows('非正 maxMessages 被拒绝', () => createSessionFollowRequest('stream-4', 's', 0), 'BAD_PAYLOAD');

    // 3. 关闭请求
    const close = createStreamCloseRequest('stream-1');
    const closePayload = close.payload as { method: string; params: { streamId: string } };
    expect('关闭走 stream.close', closePayload.method === STREAM_CLOSE_METHOD, closePayload.method);
    expect('关闭携带 streamId', closePayload.params.streamId === 'stream-1');
    expectThrows('关闭空 streamId 被拒绝', () => createStreamCloseRequest(''), 'BAD_PAYLOAD');

    // 4. 事件解析：数据帧 / 终止帧 / 未知事件
    const frameSignal = parseStreamEvent(EVENT_FRAME, { streamId: 'stream-1', hasValue: true, value: { k: 1 } });
    expect('数据帧解析为 frame', frameSignal?.kind === 'frame');
    expect('数据帧 value 保留',
        frameSignal?.kind === 'frame' && (frameSignal.value as { k: number }).k === 1);
    const emptyFrame = parseStreamEvent(EVENT_FRAME, { streamId: 'stream-1', hasValue: true });
    expect('缺省 value 为 undefined',
        emptyFrame?.kind === 'frame' && emptyFrame.value === undefined);
    expect('未知事件返回 undefined', parseStreamEvent('harness.unknown', {}) === undefined);
    expectThrows('缺 streamId 的数据帧被拒绝',
        () => parseStreamEvent(EVENT_FRAME, { hasValue: true }), 'BAD_PAYLOAD');
    expectThrows('transfer 描述帧被拒绝',
        () => parseStreamEvent(EVENT_FRAME, { streamId: 's', hasValue: true, transfer: { kind: 'chunked' } }),
        'BAD_FRAME');

    const closedSignal = parseStreamEvent(EVENT_STREAM_CLOSED, { streamId: 'stream-1', reason: 'completed' });
    expect('终止帧解析为 closed',
        closedSignal?.kind === 'closed' && closedSignal.reason === 'completed');
    const failedSignal = parseStreamEvent(EVENT_STREAM_CLOSED, {
        streamId: 'stream-1',
        reason: 'failed',
        failure: { code: 'TIMEOUT', message: '超时' },
    });
    expect('failed 带出 failure',
        failedSignal?.kind === 'closed' && failedSignal.failure?.code === 'TIMEOUT');
    const failureDetails = failedSignal?.kind === 'closed' ? failedSignal.failure?.details : undefined;
    expect('failure.details 缺省为空对象',
        failureDetails !== undefined && Object.keys(failureDetails).length === 0);
    expectThrows('未知终止原因被拒绝',
        () => parseStreamEvent(EVENT_STREAM_CLOSED, { streamId: 's', reason: 'done' }), 'BAD_PAYLOAD');

    // 5. 增量聚合：快照与工具调用忽略，文本与推理分开
    const aggregator = new AssistantStreamAggregator();
    expect('快照不产生文本', !aggregator.pushItem({ type: 'snapshot', records: [] }));
    const applied = aggregator.pushItem({
        type: 'event',
        event: { type: 'assistant/chunk', data: { chunk: { type: 'block-start', index: 0 } } },
    });
    expect('block-start 被忽略', !applied);
    aggregator.pushItem({
        type: 'event',
        event: { type: 'assistant/chunk', data: { chunk: { type: 'text-delta', index: 0, text: '你' } } },
    });
    aggregator.pushItem({
        type: 'event',
        event: { type: 'assistant/chunk', data: { chunk: { type: 'text-delta', index: 0, text: '好' } } },
    });
    aggregator.pushItem({
        type: 'assistant-stream',
        frame: { type: 'chunk', chunk: { type: 'reasoning-delta', index: 1, text: '思考' } },
    });
    aggregator.pushItem({
        type: 'event',
        event: { type: 'assistant/chunk', data: { chunk: { type: 'tool-call-delta', argumentsDelta: '{}' } } },
    });
    expect('正文增量按序拼接', aggregator.text === '你好', aggregator.text);
    expect('推理增量单独保存', aggregator.reasoning === '思考', aggregator.reasoning);
    expect('工具调用不进入正文', aggregator.chunkCount === 3, String(aggregator.chunkCount));
    aggregator.reset();
    expect('reset 清空文本与计数', aggregator.text === '' && aggregator.chunkCount === 0);

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 审批与用户问答自检。
 *
 * 钉住三件事：
 * 1. `$events` 的帧分支（ready / waterfall / cancel）与字段回退；
 * 2. 审批只有 `allowed-once` / `rejected` 两种取值；
 * 3. 回复必须带 ready 拿到的 clientId，且走 `harness.remote.call` 的 `$events/result`。
 */
export function interactionsSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const expectThrows = (name: string, run: () => unknown, code: string) => {
        try {
            run();
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };

    // 1. 打开 $events 流
    const stream = createEventsStreamRequest('events-1');
    const streamPayload = stream.payload as {
        method: string;
        params: { streamId: string; endpoint: string; payload: { args: Record<string, unknown> } };
    };
    expect('$events 走 stream.open', streamPayload.method === STREAM_OPEN_METHOD, streamPayload.method);
    expect('$events endpoint 正确',
        streamPayload.params.endpoint === EVENTS_ENDPOINT, streamPayload.params.endpoint);
    expect('$events 参数为空对象',
        Object.keys(streamPayload.params.payload.args).length === 0);

    // 2. ready 与非法帧
    const ready = parseEventsFrame({ type: 'ready', clientId: 'client-1' });
    expect('ready 解析出 clientId',
        ready?.kind === 'ready' && ready.clientId === 'client-1');
    expectThrows('ready 缺 clientId 被拒绝',
        () => parseEventsFrame({ type: 'ready' }), 'BAD_PAYLOAD');
    expectThrows('非对象帧被拒绝', () => parseEventsFrame('x'), 'BAD_PAYLOAD');

    // 3. 审批请求
    const approval = parseEventsFrame({
        type: 'waterfall',
        event: 'approval/request',
        eventId: 'evt-a-1',
        agentId: 'session-1',
        request: { toolName: 'bash', callId: 'call-1', reason: '需要删除文件' },
    });
    expect('审批请求解析为 approval', approval?.kind === 'approval');
    expect('审批字段被保留',
        approval?.kind === 'approval'
        && approval.sessionId === 'session-1'
        && approval.toolName === 'bash'
        && approval.callId === 'call-1'
        && approval.reason === '需要删除文件');
    const defaultTool = parseEventsFrame({
        type: 'waterfall',
        event: 'approval/request',
        eventId: 'evt-a-2',
        agentId: 'session-1',
        request: {},
    });
    expect('缺 toolName 时回退默认值',
        defaultTool?.kind === 'approval' && defaultTool.toolName === 'Harness tool');

    // 4. 问答请求
    const question = parseEventsFrame({
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'evt-q-1',
        agentId: 'session-1',
        request: {
            questions: [
                {
                    id: 'q1',
                    question: '选择部署方式',
                    header: '部署',
                    options: [{ label: '容器', description: 'Docker' }, { label: '裸机' }],
                    multiSelect: true,
                },
                { id: 'bad', question: '' },
                { question: '缺少 id' },
            ],
        },
    });
    expect('问答请求解析为 question', question?.kind === 'question');
    expect('坏问题被丢弃',
        question?.kind === 'question' && question.questions.length === 1,
        question?.kind === 'question' ? String(question.questions.length) : '');
    const parsedQuestion = question?.kind === 'question' ? question.questions[0] : undefined;
    expect('问题字段被保留',
        parsedQuestion?.id === 'q1' && parsedQuestion.header === '部署' && parsedQuestion.multiSelect === true);
    expect('选项描述被保留',
        parsedQuestion?.options?.length === 2 && parsedQuestion.options[0].description === 'Docker');
    const noQuestions = parseEventsFrame({
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'evt-q-2',
        agentId: 'session-1',
        request: {},
    });
    expect('缺 questions 时为空数组',
        noQuestions?.kind === 'question' && noQuestions.questions.length === 0);
    expect('未知 waterfall 事件被忽略',
        parseEventsFrame({ type: 'waterfall', event: 'other/request', eventId: 'e', agentId: 's', request: {} }) === undefined);
    expect('转发事件被忽略', parseEventsFrame({ type: 'emit', event: 'x', args: [] }) === undefined);

    // 5. cancel
    const cancel = parseEventsFrame({ type: 'cancel', eventId: 'evt-a-1' });
    expect('cancel 解析出 eventId',
        cancel?.kind === 'cancel' && cancel.eventId === 'evt-a-1');
    expectThrows('cancel 缺 eventId 被拒绝',
        () => parseEventsFrame({ type: 'cancel' }), 'BAD_PAYLOAD');

    // 6. 审批回复
    const allowed = createApprovalResultRequest('client-1', 'evt-a-1', 'allowed-once');
    const allowedPayload = allowed.payload as {
        method: string;
        params: {
            endpoint: string;
            payload: { args: { clientId: string; eventId: string; outcome: unknown } };
        };
    };
    expect('审批回复走 harness.remote.call', allowedPayload.method === REMOTE_CALL_METHOD);
    expect('审批回复 endpoint 为 $events/result',
        allowedPayload.params.endpoint === EVENTS_RESULT_ENDPOINT, allowedPayload.params.endpoint);
    const allowedArgs = allowedPayload.params.payload.args;
    expect('审批回复带 clientId 与 eventId',
        allowedArgs.clientId === 'client-1' && allowedArgs.eventId === 'evt-a-1');
    expect('allowed-once 原样作为结果值',
        (allowedArgs.outcome as { kind: string; value: string }).kind === 'result'
        && (allowedArgs.outcome as { value: string }).value === 'allowed-once');
    const rejectedOutcome = createApprovalResultRequest('client-1', 'evt-a-1', 'rejected');
    const rejectedValue = (rejectedOutcome.payload as {
        params: { payload: { args: { outcome: { value: string } } } };
    }).params.payload.args.outcome.value;
    expect('rejected 原样作为结果值', rejectedValue === 'rejected');
    expectThrows('未知审批结果被拒绝',
        () => createApprovalResultRequest('client-1', 'evt-a-1', 'always' as 'rejected'), 'BAD_PAYLOAD');
    expectThrows('缺 clientId 的回复被拒绝',
        () => createApprovalResultRequest('', 'evt-a-1', 'allowed-once'), 'BAD_PAYLOAD');

    // 7. 问答回复
    const answerRequest = createQuestionResultRequest('client-1', 'evt-q-1', [
        { id: 'q1', selected: ['容器'], custom: '加监控' },
        { id: 'q2', selected: [], custom: '' },
    ]);
    const answerValue = (answerRequest.payload as {
        params: { payload: { args: { outcome: { value: { answers: Array<Record<string, unknown>> } } } } };
    }).params.payload.args.outcome.value;
    expect('问答回复带两条答案', answerValue.answers.length === 2);
    expect('selected 原样保留', (answerValue.answers[0].selected as string[])[0] === '容器');
    expect('custom 保留', answerValue.answers[0].custom === '加监控');
    expect('空 custom 被省略', !('custom' in answerValue.answers[1]));
    expectThrows('空答案数组被拒绝',
        () => createQuestionResultRequest('client-1', 'evt-q-1', []), 'BAD_PAYLOAD');
    expectThrows('selected 类型错误被拒绝',
        () => createQuestionResultRequest('client-1', 'evt-q-1', [
            { id: 'q1', selected: [1] as unknown as string[] },
        ]), 'BAD_PAYLOAD');

    // 8. 本地拒绝
    const localReject = createRejectedResultRequest('client-1', 'evt-q-1', 'ASK_TIMED_OUT', '回答窗口结束');
    const localOutcome = (localReject.payload as {
        params: { payload: { args: { outcome: { kind: string; error: Record<string, string> } } } };
    }).params.payload.args.outcome;
    expect('本地拒绝为 rejected 分支', localOutcome.kind === 'rejected');
    expect('拒绝错误字段完整',
        localOutcome.error.name === 'UserQuestionError'
        && localOutcome.error.code === 'ASK_TIMED_OUT'
        && localOutcome.error.message === '回答窗口结束');
    expectThrows('拒绝缺 code 被拒绝',
        () => createRejectedResultRequest('client-1', 'evt-q-1', '', 'x'), 'BAD_PAYLOAD');

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 断线重连与状态恢复自检。
 *
 * 钉住三件事：
 * 1. 退避序列有界，且非法 attempt 按 1 处理；
 * 2. 连接代次能丢弃上一代回调；
 * 3. 恢复动作的顺序：先失败未知请求，再刷新，再重建流，最后清审批残留。
 */
export function recoverySelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const kinds = (actions: RecoveryAction[]) => actions.map((action) => action.kind).join(',');

    // 1. 退避序列
    expect('首次等待 500ms', nextReconnectDelay(1) === 500, String(nextReconnectDelay(1)));
    expect('第二次等待翻倍', nextReconnectDelay(2) === 1000, String(nextReconnectDelay(2)));
    expect('第三次等待 2000ms', nextReconnectDelay(3) === 2000, String(nextReconnectDelay(3)));
    expect('等待封顶 30s',
        nextReconnectDelay(20) === DEFAULT_RECONNECT_POLICY.maxDelayMs,
        String(nextReconnectDelay(20)));
    expect('attempt 0 按 1 处理', nextReconnectDelay(0) === 500);
    expect('非整数 attempt 按 1 处理', nextReconnectDelay(2.5) === 500);
    try {
        nextReconnectDelay(1, { baseDelayMs: 0, maxDelayMs: 1000, factor: 2 });
        expect('非法策略被拒绝', false, '本应抛错');
    } catch (e) {
        expect('非法策略被拒绝', e instanceof ProtocolError && e.code === 'BAD_PAYLOAD');
    }

    // 2. 连接代次
    const epoch = new ConnectionEpoch();
    expect('初始代次为 0', epoch.current === 0);
    expect('未连接时任何代次都无效', !epoch.isCurrent(0) && !epoch.isCurrent(1));
    const first = epoch.begin();
    expect('begin 后从 1 开始', first === 1);
    expect('当代次有效', epoch.isCurrent(first));
    const second = epoch.begin();
    expect('重连后代次递增', second === 2);
    expect('旧代次被丢弃', !epoch.isCurrent(first) && epoch.isCurrent(second));

    // 3. 恢复动作
    const bare = planRecovery();
    expect('无状态时仍刷新列表并重建事件流',
        kinds(bare) === 'refreshSessions,reopenEvents', kinds(bare));

    const full = planRecovery({
        activeSessionId: 'session-1',
        pendingRequestIds: ['req-1', 'req-2', ''],
        pendingApprovalEventIds: ['evt-1', ''],
    });
    expect('恢复顺序：失败 → 刷新 → 跟随 → 事件 → 清审批',
        kinds(full) === 'failPendingRequest,failPendingRequest,refreshSessions,reopenFollow,reopenEvents,dropLocalApproval',
        kinds(full));
    const failed = full.filter((action): action is Extract<RecoveryAction, { kind: 'failPendingRequest' }> =>
        action.kind === 'failPendingRequest');
    expect('每个在途请求各一条失败动作', failed.length === 2);
    expect('失败码为 UNKNOWN_OUTCOME',
        failed.every((action) => action.code === 'UNKNOWN_OUTCOME'));
    expect('空 requestId 被过滤',
        failed.every((action) => action.requestId.length > 0));
    const follow = full.find((action) => action.kind === 'reopenFollow');
    expect('跟随动作指向当前会话',
        follow?.kind === 'reopenFollow' && follow.sessionId === 'session-1');
    const dropped = full.filter((action): action is Extract<RecoveryAction, { kind: 'dropLocalApproval' }> =>
        action.kind === 'dropLocalApproval');
    expect('每条本地审批各一条清理动作',
        dropped.length === 1 && dropped[0].eventId === 'evt-1');

    const noSession = planRecovery({ activeSessionId: '' });
    expect('无当前会话时不重开跟随流',
        !noSession.some((action) => action.kind === 'reopenFollow'), kinds(noSession));

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 主机与工作区发现自检。
 *
 * 钉住两件事：
 * 1. `/api/v1/devices` 同时含 client，必须只留 role=host，且坏条目丢弃；
 * 2. 工作区来自 `workspace/follow` 的 baseline 首帧，不是 unary 返回。
 */
export function discoverySelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const expectThrows = (name: string, run: () => unknown, code: string) => {
        try {
            run();
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };

    // 1. Host 列表
    const hosts = readHostDevices({
        items: [
            {
                deviceId: 'host-1',
                membershipId: 'm-1',
                name: '工作站',
                role: 'host',
                platform: 'windows',
                online: true,
                identityKey: 'pk-1',
                clientVersion: '1.0.0',
                harnessVersion: '0.9.2',
                lastSeenAt: 1700000000000,
            },
            {
                deviceId: 'host-2',
                membershipId: 'm-1',
                name: '备用机',
                role: 'host',
                platform: 'linux',
                identityKey: 'pk-2',
            },
            { deviceId: 'client-1', membershipId: 'm-1', name: '手机', role: 'client', platform: 'android', identityKey: 'pk-3' },
            { deviceId: 'host-3', name: '缺 membershipId', role: 'host', platform: 'macos', identityKey: 'pk-4' },
            { deviceId: 'host-4', membershipId: 'm-1', role: 'host', platform: 'macos' },
        ],
    });
    expect('只保留 host 设备', hosts.length === 2, String(hosts.length));
    expect('在线状态与版本被保留',
        hosts[0].deviceId === 'host-1'
        && hosts[0].online === true
        && hosts[0].clientVersion === '1.0.0'
        && hosts[0].harnessVersion === '0.9.2'
        && hosts[0].lastSeenAt === 1700000000000);
    expect('身份公钥被保留', hosts[0].identityKey === 'pk-1', hosts[0].identityKey);
    expect('缺 online 时按离线处理', hosts[1].online === false);
    expect('坏条目被丢弃',
        !hosts.some((host) => host.deviceId === 'host-3' || host.deviceId === 'host-4'));
    expect('非对象响应返回空列表', readHostDevices('x').length === 0);
    expect('items 非数组返回空列表', readHostDevices({ items: {} }).length === 0);

    // 2. 工作区跟随流
    const follow = createWorkspaceFollowRequest('ws-1');
    const followPayload = follow.payload as {
        method: string;
        params: { streamId: string; endpoint: string; payload: { args: Record<string, unknown> } };
    };
    expect('工作区走 stream.open', followPayload.method === STREAM_OPEN_METHOD, followPayload.method);
    expect('工作区 endpoint 为 workspace/follow',
        followPayload.params.endpoint === WORKSPACE_FOLLOW_ENDPOINT, followPayload.params.endpoint);
    expect('工作区参数为空对象',
        Object.keys(followPayload.params.payload.args).length === 0);

    const baseline = readWorkspaceBaseline({
        type: 'baseline',
        value: {
            items: [
                {
                    workspaceId: 'ws-1',
                    path: 'D:/work/a',
                    title: '项目 A',
                    sessionIds: ['s-1', 2, 's-2'],
                    createdAt: '2026-11-01T00:00:00Z',
                    updatedAt: '2026-11-20T00:00:00Z',
                },
                { workspaceId: 'ws-2' },
                { title: '缺 workspaceId' },
            ],
            archivedSessionIds: ['s-old', 3],
            pinnedSessionIds: undefined,
        },
    });
    expect('基线解析出两个工作区', baseline.items.length === 2, String(baseline.items.length));
    expect('工作区字段被保留',
        baseline.items[0].workspaceId === 'ws-1'
        && baseline.items[0].title === '项目 A'
        && baseline.items[0].path === 'D:/work/a'
        && baseline.items[0].updatedAt === '2026-11-20T00:00:00Z');
    expect('sessionIds 过滤非字符串',
        baseline.items[0].sessionIds.length === 2 && baseline.items[0].sessionIds[1] === 's-2');
    expect('缺 path/title 时回退空串',
        baseline.items[1].path === '' && baseline.items[1].title === '');
    expect('归档会话过滤非字符串',
        baseline.archivedSessionIds.length === 1 && baseline.archivedSessionIds[0] === 's-old');
    expect('缺省置顶列表为空', baseline.pinnedSessionIds.length === 0);
    expect('非对象基线值容错为空列表', readWorkspaceList('x').items.length === 0);
    expectThrows('首帧不是 baseline 被拒绝',
        () => readWorkspaceBaseline({ type: 'event', value: {} }), 'BAD_PAYLOAD');
    expectThrows('baseline 缺 value 被拒绝',
        () => readWorkspaceBaseline({ type: 'baseline' }), 'BAD_PAYLOAD');

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 会话编排自检。
 *
 * 用文本帧驱动 `ConversationController`，不经过真实 WS：
 * 钉住 prompt 在途状态、流帧路由（会话流 vs `$events` 流）、
 * 审批/问答的回复与清理、以及断线时的恢复计划。
 */
export function conversationSelfTest(): { ok: boolean; checks: string[]; failures: string[] } {
    const checks: string[] = [];
    const failures: string[] = [];

    const expect = (name: string, pass: boolean, detail = '') => {
        if (pass) checks.push(name);
        else failures.push(detail.length > 0 ? `${name}：${detail}` : name);
    };

    const expectThrows = (name: string, run: () => unknown, code: string) => {
        try {
            run();
            expect(name, false, '本应抛错却通过了');
        } catch (e) {
            const actual = e instanceof ProtocolError ? e.code : 'NOT_PROTOCOL_ERROR';
            expect(name, actual === code, `期望 ${code}，实际 ${actual}`);
        }
    };

    let seq = 0;
    const eventMessage = (event: string, data: unknown) => JSON.stringify({
        v: 1,
        id: `t-${++seq}`,
        type: 'event',
        timestamp: 1,
        payload: { event, data },
    });
    const response = (requestId: string) => JSON.stringify({
        v: 1,
        id: `t-${++seq}`,
        type: 'rpc.response',
        timestamp: 1,
        payload: { requestId, result: { ok: true } },
    });
    const error = (requestId: string, code: string, message: string) => JSON.stringify({
        v: 1,
        id: `t-${++seq}`,
        type: 'rpc.error',
        timestamp: 1,
        payload: { requestId, code, message, retryable: false },
    });
    const streamValue = (streamId: string, value: unknown) =>
        eventMessage('harness.remote.frame', { streamId, hasValue: true, value });
    const streamClosed = (streamId: string, reason: string) =>
        eventMessage('harness.remote.stream.closed', { streamId, reason });

    const texts: string[] = [];
    const failed: string[] = [];
    const approvals: string[] = [];
    const questions: string[] = [];
    let ready: string | undefined;
    let cancelled = '';
    let closed = '';

    const controller = new ConversationController({
        onText: (text) => texts.push(text),
        onRequestFailed: (requestId, code) => failed.push(`${requestId}:${code}`),
        onClientReady: (clientId) => { ready = clientId; },
        onApproval: (request) => approvals.push(request.eventId),
        onQuestion: (request) => questions.push(request.eventId),
        onInteractionCancelled: (eventId) => { cancelled = eventId; },
        onStreamClosed: (reason) => { closed = reason; },
    });

    // 1. 会话绑定
    expectThrows('未选会话不能发 Prompt', () => controller.sendPrompt('hi'), 'BAD_PAYLOAD');
    const follow = controller.attachSession('session-1', 'follow-1');
    const followPayload = follow.payload as {
        method: string;
        params: { streamId: string; endpoint: string; payload: { args: { request: { address: { kind: string; sessionId: string } } } } };
    };
    expect('绑定会话走 session/follow',
        followPayload.params.endpoint === 'session/follow', followPayload.params.endpoint);
    expect('跟随流 id 被登记', followPayload.params.streamId === 'follow-1');
    expect('跟随地址指向会话',
        followPayload.params.payload.args.request.address.kind === 'session'
        && followPayload.params.payload.args.request.address.sessionId === 'session-1');
    expect('会话 id 可读取', controller.sessionId === 'session-1');

    const events = controller.openEvents('events-1');
    expect('事件流 endpoint 为 $events',
        (events.payload as { params: { endpoint: string } }).params.endpoint === '$events');

    // 2. Prompt 在途状态
    const prompt = controller.sendPrompt('你好');
    const promptPayload = prompt.payload as {
        params: {
            endpoint: string;
            payload: { args: { request: { requestId: string; sessionId: string; mode: string; content: Array<{ type: string; text: string }> } } };
        };
    };
    const promptRequest = promptPayload.params.payload.args.request;
    expect('Prompt endpoint 为 session/prompt', promptPayload.params.endpoint === 'session/prompt');
    expect('Prompt 文本正确', promptRequest.content[0].text === '你好');
    expect('Prompt 带会话 id 与 queue 模式',
        promptRequest.sessionId === 'session-1' && promptRequest.mode === 'queue');
    expect('Prompt 已登记为在途', controller.pending.size === 1 && controller.streaming);

    const second = controller.sendPrompt('第二条', 'client-req-1');
    const secondRequest = (second.payload as {
        params: { payload: { args: { request: { requestId: string } } } };
    }).params.payload.args.request;
    expect('显式 requestId 原样保留', secondRequest.requestId === 'client-req-1');

    controller.handleMessage(response(prompt.id));
    expect('响应后仍在流式中（还有一条在途）',
        controller.pending.size === 1 && controller.streaming);
    controller.handleMessage(error(second.id, 'SESSION_BUSY', '会话忙'));
    expect('失败回调带错误码',
        failed.length === 1 && failed[0].endsWith('SESSION_BUSY'), failed.join(','));
    expect('全部结算后停止流式', !controller.streaming && controller.pending.size === 0);

    // 3. 会话流帧 → 聚合器
    controller.handleMessage(streamValue('follow-1', {
        type: 'event',
        event: { type: 'assistant/chunk', data: { chunk: { type: 'text-delta', text: '你好' } } },
    }));
    controller.handleMessage(streamValue('follow-1', {
        type: 'assistant/chunk',
        frame: undefined,
    }));
    controller.handleMessage(streamValue('follow-1', {
        type: 'assistant-stream',
        frame: { type: 'chunk', chunk: { type: 'text-delta', text: '，世界' } },
    }));
    controller.handleMessage(streamValue('follow-1', {
        type: 'assistant-stream',
        frame: { type: 'chunk', chunk: { type: 'reasoning-delta', text: '思考' } },
    }));
    expect('文本增量被累计', controller.aggregator.text === '你好，世界', controller.aggregator.text);
    expect('onText 收到累计值', texts.length === 3 && texts[2] === '你好，世界', String(texts.length));
    expect('推理增量单独累计', controller.aggregator.reasoning === '思考');
    expect('未知事件被忽略',
        (controller.handleMessage(eventMessage('other/event', { streamId: 'follow-1' })), true));

    controller.handleMessage(streamClosed('follow-1', 'completed'));
    expect('流结束回调原因', closed === 'completed', closed);
    expect('流结束后不再流式', !controller.streaming);

    // 4. $events 流
    controller.handleMessage(streamValue('events-1', { type: 'ready', clientId: 'client-1' }));
    expect('ready 写入 clientId',
        ready === 'client-1' && controller.clientId === 'client-1');

    controller.handleMessage(streamValue('events-1', {
        type: 'waterfall',
        event: 'approval/request',
        eventId: 'evt-a',
        agentId: 'session-1',
        request: { toolName: 'bash' },
    }));
    expect('审批请求进入待办', approvals.length === 1 && controller.pendingInteractions.length === 1);

    const approvalReply = controller.approve('evt-a', 'allowed-once');
    const approvalOutcome = (approvalReply.payload as {
        params: { endpoint: string; payload: { args: { outcome: { kind: string; value: string } } } };
    }).params;
    expect('审批回复走 $events/result', approvalOutcome.endpoint === '$events/result');
    expect('审批结果值正确',
        approvalOutcome.payload.args.outcome.kind === 'result'
        && approvalOutcome.payload.args.outcome.value === 'allowed-once');
    expect('回复后移除本地审批', controller.pendingInteractions.length === 0);

    controller.handleMessage(streamValue('events-1', {
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'evt-q',
        agentId: 'session-1',
        request: { questions: [{ id: 'q1', question: '选一个' }] },
    }));
    expect('问答请求进入待办', questions.length === 1 && controller.pendingInteractions.length === 1);
    const answerReply = controller.answerQuestion('evt-q', [{ id: 'q1', selected: ['A'] }]);
    const answerValue = (answerReply.payload as {
        params: { payload: { args: { outcome: { value: { answers: Array<{ id: string; selected: string[] }> } } } } };
    }).params.payload.args.outcome.value;
    expect('问答答案被编码',
        answerValue.answers.length === 1 && answerValue.answers[0].selected[0] === 'A');
    expect('回答后移除本地问答', controller.pendingInteractions.length === 0);

    controller.handleMessage(streamValue('events-1', {
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'evt-r',
        agentId: 'session-1',
        request: { questions: [] },
    }));
    const rejectReply = controller.rejectInteraction('evt-r', 'ASK_TIMED_OUT', '窗口结束');
    expect('本地拒绝走 rejected 分支',
        (rejectReply.payload as {
            params: { payload: { args: { outcome: { kind: string } } } };
        }).params.payload.args.outcome.kind === 'rejected');

    controller.handleMessage(streamValue('events-1', {
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'evt-c',
        agentId: 'session-1',
        request: { questions: [] },
    }));
    controller.handleMessage(streamValue('events-1', { type: 'cancel', eventId: 'evt-c' }));
    expect('cancel 移除待办并回调',
        cancelled === 'evt-c' && controller.pendingInteractions.length === 0);

    // 5. 切换会话与断线
    controller.attachSession('session-2', 'follow-2');
    expect('切换会话重置聚合文本',
        controller.aggregator.text === '' && controller.sessionId === 'session-2');

    const lastPrompt = controller.sendPrompt('断线前的最后一条');
    const plan = controller.disconnect();
    const kinds = plan.map((action) => action.kind).join(',');
    expect('断线计划包含全部恢复步骤',
        kinds === 'failPendingRequest,refreshSessions,reopenFollow,reopenEvents', kinds);
    expect('在途 prompt 被标记未知',
        plan[0].kind === 'failPendingRequest' && plan[0].requestId === lastPrompt.id);
    expect('断线清空 clientId 与流式状态',
        controller.clientId === undefined && !controller.streaming && controller.pending.size === 0);
    expect('断线后不能回复审批',
        (() => {
            try {
                controller.approve('evt-x', 'rejected');
                return false;
            } catch (e) {
                return e instanceof ProtocolError && e.code === 'BAD_PAYLOAD';
            }
        })());

    return { ok: failures.length === 0, checks, failures };
}
