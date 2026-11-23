// Control 通道的状态机与拉取循环。
//
// 依据：上游 `docs/plugin-integration.md` §7（连接与 hello）、§8（心跳）、§10（连接协商），
//       `packages/protocol/src/index.ts`（帧定义）
//
// ## 为什么状态机是同步的
//
// QuickJS 里没有 `setTimeout`，也没有线程：所有等待都发生在宿主函数的 Promise 上。
// 若把「收到一帧该做什么」和「怎么拿到下一帧」写在一起，这段逻辑就只能靠真连一台
// Server 才能测。因此拆成两层：
//
// - `ControlChannel.handle(text)`：纯同步转移，输入帧文本、输出要发的帧与回调；
// - `openControl()`：薄薄一层拉取循环，只负责 `ctx.ws.recv` / `send` 与异常分类。
//
// 这样 90% 的行为（首帧必须是 hello.ack、心跳回 pong、各类型分发、未知类型忽略）
// 都能在没有网络的用例里钉死。

/// <reference path="../types/index.d.ts" />

import { deriveWsUrl } from './api.js';
import {
    ProtocolError,
    createFrame,
    decodeFrame,
    encodeFrame,
    readConnectIncoming,
    readControlError,
    readHelloAck,
    readPingPongNonce,
    readRelay,
    readSecureHandshake,
} from './protocol.js';
import type {
    ControlError,
    ControlFrameType,
    ConnectIncoming,
    HelloAck,
    RelayFrame,
    SecureHandshakeFrame,
} from './protocol.js';

export interface ControlHandlers {
    onReady?(ack: HelloAck): void;
    onConnectIncoming?(frame: ConnectIncoming): void;
    onConnectAccepted?(payload: Record<string, unknown>): void;
    onConnectRejected?(payload: Record<string, unknown>): void;
    onSecureHandshake?(frame: SecureHandshakeFrame): void;
    onRelay?(frame: RelayFrame): void;
    onServerError?(error: ControlError): void;
    /** 收到首期不支持的帧类型（WebRTC 信令、租约等）。只做记录，不影响连接 */
    onUnsupported?(type: ControlFrameType): void;
}

export interface ControlStep {
    /** 需要立刻发出的帧文本（目前只有 pong） */
    sends: string[];
    /** 首帧 hello.ack 解析结果，仅在握手完成那一步出现 */
    ack?: HelloAck;
    /** 服务器下发的错误帧 */
    error?: ControlError;
}

export interface ControlFrame<T = unknown> {
    v: number;
    id: string;
    type: ControlFrameType;
    timestamp: number;
    payload: T;
}

export class ControlStateError extends Error {
    constructor(readonly code: 'NOT_READY' | 'ALREADY_READY' | 'BAD_FIRST_FRAME' | 'CLOSED', message: string) {
        super(message);
    }
}

/** hello 的入参。字段与 §7 一一对应，`capabilities` 留空表示「什么扩展都不声明」 */
export interface HelloPayload {
    deviceId: string;
    accessToken: string;
    /** LifeBoard 是 client。写成常量更不容易在调用处写错 */
    role: 'client';
    capabilities?: string[];
}

const UNSUPPORTED_TYPES = new Set<string>([
    'signal.offer',
    'signal.answer',
    'signal.ice',
    'connection.lease.request',
    'connection.lease.ack',
    'connection.lease.close',
]);

export class ControlChannel {

    private phase: 'greeting' | 'ready' | 'closed' = 'greeting';

    constructor(private readonly handlers: ControlHandlers = {}) {}

    get ready(): boolean { return this.phase === 'ready'; }

    /**
     * 构造 `hello` 帧文本。
     *
     * 必须在连接建立后**立刻**发出（§7：5 秒内），所以这里只产文本，
     * 由调用方紧接着 `send`——中间不插任何 await，避免被别的等待挤到后面。
     */
    createHello(payload: HelloPayload): string {
        if (this.phase === 'closed') throw new ControlStateError('CLOSED', '连接已关闭');
        if (payload.deviceId.length === 0) throw new ControlStateError('NOT_READY', 'hello 缺少 deviceId');
        if (payload.accessToken.length === 0) throw new ControlStateError('NOT_READY', 'hello 缺少 accessToken');
        return encodeFrame(createFrame('hello', {
            role: payload.role,
            deviceId: payload.deviceId,
            accessToken: payload.accessToken,
            protocols: [1],
            capabilities: payload.capabilities ?? [],
        }));
    }

    /** 构造任意出站帧（`connect.request`、`secure.handshake` 等） */
    send(type: ControlFrameType, payload: unknown): string {
        if (this.phase === 'closed') throw new ControlStateError('CLOSED', '连接已关闭');
        if (type === 'hello') throw new ControlStateError('NOT_READY', 'hello 只能由 createHello 构造');
        return encodeFrame(createFrame(type, payload));
    }

    /**
     * 处理一条入站帧文本。
     *
     * 未知帧类型**忽略**而不是断开：协议会演进，服务端先上新类型不应把旧客户端踢下线。
     * 但畸形帧（非 JSON、版本不符、缺字段）必须抛错——那说明两端理解根本不一致，
     * 继续读下去只会错得更远。
     */
    handle(text: string): ControlStep {
        if (this.phase === 'closed') throw new ControlStateError('CLOSED', '连接已关闭');

        let frame: ControlFrame<Record<string, unknown>>;
        try {
            frame = decodeFrame(text) as ControlFrame<Record<string, unknown>>;
        } catch (e) {
            if (e instanceof ProtocolError && e.code === 'UNKNOWN_TYPE') {
                // 类型本身未知，解不出 payload，只能记录
                this.handlers.onUnsupported?.('<unknown>' as ControlFrameType);
                return { sends: [] };
            }
            throw e;
        }

        if (this.phase === 'greeting') {
            if (frame.type === 'error') {
                const error = readControlError(frame.payload);
                this.phase = 'closed';
                throw new ControlStateError('BAD_FIRST_FRAME', `建连被拒绝：${error.code} ${error.message}`);
            }
            if (frame.type !== 'hello.ack') {
                throw new ControlStateError('BAD_FIRST_FRAME', `首帧应为 hello.ack，实际 ${frame.type}`);
            }
            const ack = readHelloAck(frame.payload);
            this.phase = 'ready';
            this.handlers.onReady?.(ack);
            return { sends: [], ack };
        }

        switch (frame.type) {
            case 'ping':
                // 心跳按「原样带回」回 pong：nonce 由服务端生成，自己造一个不解决问题
                return { sends: [this.send('pong', { nonce: readPingPongNonce(frame.payload) })] };
            case 'pong':
                return { sends: [] };
            case 'error': {
                const error = readControlError(frame.payload);
                this.handlers.onServerError?.(error);
                return { sends: [], error };
            }
            case 'connect.incoming':
                this.handlers.onConnectIncoming?.(readConnectIncoming(frame.payload));
                return { sends: [] };
            case 'connect.accepted':
                this.handlers.onConnectAccepted?.(frame.payload);
                return { sends: [] };
            case 'connect.rejected':
                this.handlers.onConnectRejected?.(frame.payload);
                return { sends: [] };
            case 'secure.handshake':
                this.handlers.onSecureHandshake?.(readSecureHandshake(frame.payload));
                return { sends: [] };
            case 'relay':
                this.handlers.onRelay?.(readRelay(frame.payload));
                return { sends: [] };
            case 'hello.ack':
                // 重复的 ack：不致命，但也不该出现，忽略即可
                return { sends: [] };
            default:
                if (UNSUPPORTED_TYPES.has(frame.type)) {
                    this.handlers.onUnsupported?.(frame.type);
                    return { sends: [] };
                }
                this.handlers.onUnsupported?.(frame.type);
                return { sends: [] };
        }
    }

    /** 标记关闭。之后 `send` / `handle` 都会抛错，避免对已死的连接写帧 */
    close(): void {
        this.phase = 'closed';
    }
}

/**
 * 连上 Server 并跑到连接终结。
 *
 * 只做三件事：建连、发 hello、循环拉帧喂给 [ControlChannel]。
 * 异常分类很重要——`ProtocolError` 说明协议不一致（重连也没用），
 * `ControlStateError` 说明对端拒绝（要看 code 决定是否重试），
 * 其余多半是网络抖动（可以退避重连）。这里不自己重连，交给调用方：
 * 重连策略要参考设备凭据刷新，混在这里会让两件事互相牵扯。
 */
export async function openControl(
    serverUrl: string,
    hello: HelloPayload,
    handlers: ControlHandlers,
): Promise<{ ack: HelloAck }> {
    const url = deriveWsUrl(serverUrl);
    const id = await ctx.ws.connect(url);

    let ack: HelloAck | undefined;
    const channel = new ControlChannel({
        ...handlers,
        onReady: (value) => {
            ack = value;
            handlers.onReady?.(value);
        },
    });

    try {
        // 先发 hello 再进循环：§7 要求 5 秒内送达
        ctx.ws.send(id, channel.createHello(hello));

        for (;;) {
            const frame = await ctx.ws.recv(id);
            if (frame.type === 'close') {
                channel.close();
                return { ack: requireAck(ack) };
            }
            // 首期只走文本控制帧；二进制留给后续数据通道
            if (frame.type !== 'text') continue;

            const step = channel.handle(frame.data);
            for (const text of step.sends) ctx.ws.send(id, text);
        }
    } finally {
        // 无论正常结束还是抛错都要关：留着 socket 会一直占着宿主连接数
        try {
            ctx.ws.close(id, 1000, 'client done');
        } catch {
            // 已经断了就不必再关，忽略
        }
    }
}

function requireAck(ack: HelloAck | undefined): HelloAck {
    if (ack === undefined) throw new ControlStateError('CLOSED', '连接在 hello 握手完成前就关闭了');
    return ack;
}
