// Control 通道的帧封装与校验。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/protocol/src/index.ts`（`controlFrameSchema` 与各 payload schema）、
//       `docs/protocol.md` §10-§23
//
// ## 为什么不是照搬那份 schema
//
// 上游用 zod 描述协议（约 195 处 `z.*`），而这里跑在 QuickJS：
// 引入 zod 会让产物多出几百 KB，且每帧都要跑一遍完整对象校验，代价与收益不成比例。
//
// 取舍是**只校验我们会用到的字段**，且只校验**入站**（服务端来的字节）。
// 出站帧由我们自己构造，字段拼错在真机上第一次连就能看见，
// 没必要为此付出体积代价。
//
// ## 三个常量必须与上游一致
//
// `PROTOCOL_VERSION`、`CONTROL_FRAME_TYPES`、`MAX_CONTROL_FRAME_BYTES`
// 一旦与对端不同，表现是握手阶段被服务端拒绝或静默丢帧。
// 因此它们照抄上游，并在注释里标出出处，改之前先确认上游是否也改了。

/// <reference path="../types/index.d.ts" />

/** 协议版本。`hello` 里的 `protocols` 与每帧的 `v` 都必须是它 */
export const PROTOCOL_VERSION = 1;

/** 单帧控制消息上限（字节）。超过服务端会直接断开连接 */
export const MAX_CONTROL_FRAME_BYTES = 64 * 1024;

/**
 * 控制帧类型全集，顺序与上游 `controlFrameTypes` 一致。
 *
 * 不是所有类型都会被本插件用到：首期走 Relay + 加密通道，
 * WebRTC 相关的 `signal.*`、`connection.lease.*` 只保留定义，
 * 用于「收到就明确拒绝」而不是当成未知类型静默丢弃。
 */
export const CONTROL_FRAME_TYPES = [
    'hello',
    'hello.ack',
    'connect.request',
    'connect.incoming',
    'connect.accepted',
    'connect.rejected',
    'secure.handshake',
    'relay',
    'signal.offer',
    'signal.answer',
    'signal.ice',
    'transport.selected',
    'connection.lease.request',
    'connection.lease.ack',
    'connection.lease.close',
    'ping',
    'pong',
    'error',
] as const;

export type ControlFrameType = typeof CONTROL_FRAME_TYPES[number];

/** 协议层错误。`code` 用于界面提示与日志分类，不直接给用户看 */
export class ProtocolError extends Error {
    constructor(
        readonly code:
            | 'NOT_JSON'
            | 'BAD_FRAME'
            | 'UNKNOWN_TYPE'
            | 'BAD_PAYLOAD'
            | 'UNSUPPORTED',
        message: string,
    ) {
        super(message);
    }
}

export interface ControlFrame<T = unknown> {
    v: number;
    id: string;
    type: ControlFrameType;
    timestamp: number;
    payload: T;
}

const TYPE_SET = new Set<string>(CONTROL_FRAME_TYPES);

/**
 * 生成帧 id。
 *
 * 只要求「同一次连接内不重复」，不做安全用途，因此不用 UUID：
 * QuickJS 没有 `crypto.randomUUID`，用宿主随机源拼一个即可。
 */
export function newFrameId(): string {
    const bytes = ctx.crypto.randomBytes(8);
    let suffix = '';
    for (let i = 0; i < bytes.length; i += 1) suffix += bytes[i].toString(16).padStart(2, '0');
    return `f_${Date.now().toString(36)}_${suffix}`;
}

/** 构造一帧。`payload` 原样放进 `payload`，不做深拷贝 */
export function createFrame<T>(type: ControlFrameType, payload: T): ControlFrame<T> {
    return {
        v: PROTOCOL_VERSION,
        id: newFrameId(),
        type,
        timestamp: Date.now(),
        payload,
    };
}

/** 序列化成 WS 文本帧。超限直接抛错，避免被服务端静默断开 */
export function encodeFrame(frame: ControlFrame): string {
    const text = JSON.stringify(frame);
    // 按 UTF-8 字节算：中文在 JSON 里是原文，字符数会低估体积
    const bytes = ctx.codec.utf8Encode(text).length;
    if (bytes > MAX_CONTROL_FRAME_BYTES) {
        throw new ProtocolError('BAD_FRAME', `控制帧 ${bytes} 字节，超过上限 ${MAX_CONTROL_FRAME_BYTES}`);
    }
    return text;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 解析入站控制帧。
 *
 * 校验到「能安全取字段」为止：版本、类型、时间戳、payload 是对象。
 * payload 内部字段由各自的使用方校验（例如 `readHelloAck`），
 * 因为不同帧的必填项差别很大，集中校验只会逼出一堆可选字段。
 */
export function decodeFrame(text: string): ControlFrame {
    let value: unknown;
    try {
        value = JSON.parse(text);
    } catch {
        throw new ProtocolError('NOT_JSON', '收到非 JSON 的控制帧');
    }
    if (!isObject(value)) throw new ProtocolError('BAD_FRAME', '控制帧不是对象');

    const { v, id, type, timestamp, payload } = value;
    if (v !== PROTOCOL_VERSION) throw new ProtocolError('BAD_FRAME', `协议版本不符：${String(v)}`);
    if (typeof id !== 'string' || id.length === 0) throw new ProtocolError('BAD_FRAME', '控制帧缺少 id');
    if (typeof type !== 'string' || !TYPE_SET.has(type)) {
        throw new ProtocolError('UNKNOWN_TYPE', `未知控制帧类型：${String(type)}`);
    }
    if (typeof timestamp !== 'number' || !Number.isInteger(timestamp) || timestamp <= 0) {
        throw new ProtocolError('BAD_FRAME', '控制帧时间戳非法');
    }
    if (!isObject(payload)) throw new ProtocolError('BAD_FRAME', `控制帧 ${type} 的 payload 不是对象`);

    return { v, id, type: type as ControlFrameType, timestamp, payload };
}

// ---------------------------------------------------------------------------
// 各帧 payload 的读取器
// ---------------------------------------------------------------------------

function requireString(source: Record<string, unknown>, key: string, frame: string): string {
    const value = source[key];
    if (typeof value !== 'string' || value.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', `${frame} 缺少字符串字段 ${key}`);
    }
    return value;
}

function optionalString(source: Record<string, unknown>, key: string): string | undefined {
    const value = source[key];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export interface HelloAck {
    serverVersion: string;
    connectionSessionId: string;
    heartbeatIntervalMs: number;
    maxControlFrameBytes: number;
    maxRelayFrameBytes: number;
    webrtcEnabled: boolean;
}

/** `hello.ack`：连上之后第一帧，决定心跳与单帧上限 */
export function readHelloAck(payload: Record<string, unknown>): HelloAck {
    const protocol = payload.protocol;
    if (protocol !== PROTOCOL_VERSION) {
        throw new ProtocolError('BAD_PAYLOAD', `服务端协议版本 ${String(protocol)}，本插件只支持 ${PROTOCOL_VERSION}`);
    }
    const heartbeat = payload.heartbeatIntervalMs;
    const maxControl = payload.maxControlFrameBytes;
    const maxRelay = payload.maxRelayFrameBytes;
    if (typeof heartbeat !== 'number' || !Number.isFinite(heartbeat) || heartbeat <= 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'hello.ack 的 heartbeatIntervalMs 非法');
    }
    return {
        serverVersion: requireString(payload, 'serverVersion', 'hello.ack'),
        connectionSessionId: requireString(payload, 'connectionSessionId', 'hello.ack'),
        heartbeatIntervalMs: heartbeat,
        // 服务端可能给出比本地常量更小的上限，取小值才安全
        maxControlFrameBytes: typeof maxControl === 'number' && maxControl > 0
            ? Math.min(maxControl, MAX_CONTROL_FRAME_BYTES)
            : MAX_CONTROL_FRAME_BYTES,
        maxRelayFrameBytes: typeof maxRelay === 'number' && maxRelay > 0 ? maxRelay : 1024 * 1024,
        webrtcEnabled: payload.webrtcEnabled === true,
    };
}

export interface ConnectIncoming {
    connectionId: string;
    clientDeviceId: string;
    clientIdentityKey: string;
    authorization: string;
    relayEndpoint?: string;
    relayTicket?: string;
    relayNodeId?: string;
    relayEpoch?: number;
}

/**
 * `connect.incoming`：有客户端请求连接本机。
 *
 * 首期只支持 `relay` 传输，因此这里**不读** `preferredTransports`：
 * 读进来也没有别的分支可走，反而会让人以为支持了 P2P。
 * 真正需要判断时再补，避免出现「字段读了但没用」的假能力。
 */
export function readConnectIncoming(payload: Record<string, unknown>): ConnectIncoming {
    const authorization = payload.authorization;
    if (authorization !== 'account') {
        throw new ProtocolError('UNSUPPORTED', `不支持的授权方式：${String(authorization)}`);
    }
    const epoch = payload.relayEpoch;
    return {
        connectionId: requireString(payload, 'connectionId', 'connect.incoming'),
        clientDeviceId: requireString(payload, 'clientDeviceId', 'connect.incoming'),
        clientIdentityKey: requireString(payload, 'clientIdentityKey', 'connect.incoming'),
        authorization,
        relayEndpoint: optionalString(payload, 'relayEndpoint'),
        relayTicket: optionalString(payload, 'relayTicket'),
        relayNodeId: optionalString(payload, 'relayNodeId'),
        relayEpoch: typeof epoch === 'number' && Number.isInteger(epoch) && epoch >= 0 ? epoch : undefined,
    };
}

export interface SecureHandshakeFrame {
    connectionId: string;
    targetDeviceId: string;
    step: 1 | 2;
    data: string;
}

/** `secure.handshake`：Noise 握手的载体，`data` 是 base64url 的握手字节 */
export function readSecureHandshake(payload: Record<string, unknown>): SecureHandshakeFrame {
    const step = payload.step;
    if (step !== 1 && step !== 2) {
        throw new ProtocolError('BAD_PAYLOAD', `secure.handshake 的 step 只能是 1 或 2，实际 ${String(step)}`);
    }
    return {
        connectionId: requireString(payload, 'connectionId', 'secure.handshake'),
        targetDeviceId: requireString(payload, 'targetDeviceId', 'secure.handshake'),
        step,
        data: requireString(payload, 'data', 'secure.handshake'),
    };
}

export interface RelayFrame {
    connectionId: string;
    targetDeviceId: string;
    counter: number;
    ciphertext: string;
}

/**
 * `relay`：加密后的业务帧。
 *
 * `counter` 是 Noize 发送序号，由对端校验。这里只检查形态，
 * 序号是否连续必须由 `NoiseIkSession` 判断——那是密码层的事，
 * 插件在协议层再判一次只会让两个错误来源混在一起。
 */
export function readRelay(payload: Record<string, unknown>): RelayFrame {
    const counter = payload.counter;
    if (typeof counter !== 'number' || !Number.isInteger(counter) || counter < 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'relay 的 counter 非法');
    }
    return {
        connectionId: requireString(payload, 'connectionId', 'relay'),
        targetDeviceId: requireString(payload, 'targetDeviceId', 'relay'),
        counter,
        ciphertext: requireString(payload, 'ciphertext', 'relay'),
    };
}

/** `ping` / `pong`：心跳。回 `pong` 时把 `nonce` 原样带回 */
export function readPingPongNonce(payload: Record<string, unknown>): string {
    return requireString(payload, 'nonce', 'ping/pong');
}

export interface ControlError {
    code: string;
    message: string;
    retryable: boolean;
}

/** 服务端下发的错误帧 */
export function readControlError(payload: Record<string, unknown>): ControlError {
    return {
        code: requireString(payload, 'code', 'error'),
        message: requireString(payload, 'message', 'error'),
        retryable: payload.retryable === true,
    };
}
