// 加密通道内的业务消息：RPC 请求/响应/错误与事件。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/protocol/src/index.ts`（`remoteMessageSchema`、`rpcRequestPayloadSchema`、
//       `rpcResponsePayloadSchema`、`rpcErrorPayloadSchema`、`EventPayload`）、
//       `packages/client-core/src/index.ts`（`RemoteClientCore.handleMessage`）、
//       `docs/protocol.md` §12-§23
//
// ## 和控制帧的区别
//
// `protocol.ts` 处理的是 WS 上的**控制帧**（明文，服务端中转）。
// 这里处理的是业务消息：它们被 Noize 加密后装进 `relay` 控制帧，
// 只有对端 Host 能解开。信封形状相同（`{v,id,type,timestamp,payload}`），
// 但类型集合、体积上限、错误语义都不一样，所以单独成文件而不是塞进 `protocol.ts`。
//
// ## 为什么不做方法名白名单
//
// 上游有约 60 个 RPC 方法与 13 个事件名，而协议演进时新增方法与事件
// **不需要**升 `PROTOCOL_VERSION`。这里如果把名单写死，对端一升级就整条通道不可用。
// 因此出站只校验「方法名非空」，入站只校验「事件名非空」，未知的一律交给调用方决定
// ——这与控制帧「未知类型忽略不断连」的取舍一致。
//
// ## 二进制上传不在本期
//
// 上游 `binary-upload.ts` 为 `workspace.upload.chunk.binary` 定义了专用二进制帧。
// 首期不做文件与终端，因此这里只处理纯文本 JSON；遇到二进制帧在更下层就会被拒。

/// <reference path="../types/index.d.ts" />

import { newFrameId, PROTOCOL_VERSION, ProtocolError } from './protocol.js';

/**
 * 单条业务消息上限（字节）。
 *
 * 取 Relay 控制帧上限：业务消息最终装进 `relay`，超过这个值发不出去。
 * 上游对大响应走 `harness.remote.transfer.*` 分块，首期不实现。
 */
export const MAX_REMOTE_MESSAGE_BYTES = 1024 * 1024;

/**
 * 业务消息类型全集，与上游 `messageTypes` 一致。
 *
 * 比控制帧稳定得多：只有这四种，新增能力走新的 RPC 方法或事件名，
 * 而不是新的消息类型。
 */
export const MESSAGE_TYPES = [
    'rpc.request',
    'rpc.response',
    'rpc.error',
    'event',
] as const;

export type MessageType = typeof MESSAGE_TYPES[number];

const MESSAGE_TYPE_SET = new Set<string>(MESSAGE_TYPES);

/** 业务消息信封，字段与控制帧同名同义 */
export interface RemoteMessage<TPayload = unknown> {
    v: typeof PROTOCOL_VERSION;
    id: string;
    type: MessageType;
    timestamp: number;
    payload: TPayload;
}

export interface RpcRequestPayload<TParams = unknown> {
    method: string;
    params: TParams;
}

export interface RpcResponsePayload<TResult = unknown> {
    requestId: string;
    result: TResult;
}

export interface RpcErrorPayload {
    requestId: string;
    /** §23 定义的稳定错误码；子系统也可自定义（如 `CODEX_*`），原样保留 */
    code: string;
    message: string;
    /** 缺省为 false：没写就是「重试解决不了问题」 */
    retryable: boolean;
    details?: unknown;
}

export interface EventPayload<TData = unknown> {
    event: string;
    data: TData;
    /** 事件序号，断线恢复时用来判断有没有漏事件；可缺省 */
    seq?: number;
    sessionId?: string;
}

// ---------------------------------------------------------------------------
// 构造与编解码
// ---------------------------------------------------------------------------

/**
 * 构造一条 RPC 请求并返回**纯对象**（还没序列化）。
 *
 * 调用方拿到 `id` 后先登记到 `RpcPending`，再 `encodeMessage` 发出去；
 * 顺序不能反——对端响应可能在发送返回前就到了。
 */
export function createRpcRequest<TParams>(
    method: string,
    params: TParams,
): RemoteMessage<RpcRequestPayload<TParams>> {
    if (typeof method !== 'string' || method.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'RPC 方法名不能为空');
    }
    return {
        v: PROTOCOL_VERSION,
        id: newFrameId(),
        type: 'rpc.request',
        timestamp: Date.now(),
        payload: { method, params },
    };
}

/**
 * 序列化为 WS 文本帧。
 *
 * `limitBytes` 默认 {@link MAX_REMOTE_MESSAGE_BYTES}；握手后可用服务端给的
 * `maxRelayFrameBytes` 覆盖。按 UTF-8 字节算上限：中文在 JSON 里是原文，
 * 按字符数会低估体积。
 */
export function encodeMessage(
    message: RemoteMessage,
    limitBytes: number = MAX_REMOTE_MESSAGE_BYTES,
): string {
    const text = JSON.stringify(message);
    const bytes = ctx.codec.utf8Encode(text).length;
    if (bytes > limitBytes) {
        throw new ProtocolError('BAD_FRAME', `业务消息 ${bytes} 字节，超过上限 ${limitBytes}`);
    }
    return text;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 远端 Gateway 拒绝请求时的错误。
 *
 * 与 `ProtocolError` 分开：那个表示「字节不符合协议」，这个表示「Host 收到了
 * 但业务上拒绝」（会话不存在、权限不足）。调用方对两者的处理完全不同——
 * 前者该报 bug，后者该提示用户或刷新列表。
 */
export class RemoteCallError extends Error {
    constructor(
        readonly code: string,
        message: string,
        readonly details?: unknown,
    ) {
        super(message);
    }
}

/**
 * 拆掉 `harness.remote.call` 的返回值外壳。
 *
 * Host 的返回有两种历史形态：`{ok:true, value}` / `{ok:false, error}`，
 * 以及直接返回业务值。这里两种都接受：没有 `ok` 字段就当业务值本身，
 * 与上游 `callValue` 的兼容逻辑一致。
 *
 * @throws {RemoteCallError} Host 明确拒绝
 * @throws {ProtocolError} 失败外壳字段缺失，说明两端理解不一致
 */
export function unwrapRemoteResult(value: unknown): unknown {
    if (!isObject(value) || !('ok' in value)) return value;
    if (value.ok === true) return value.value;
    const error = value.error;
    if (value.ok !== false || !isObject(error)
        || typeof error.code !== 'string' || error.code.length === 0
        || typeof error.message !== 'string' || error.message.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', '远端返回的失败结果格式非法');
    }
    throw new RemoteCallError(error.code, error.message, error.details);
}

/**
 * 解析入站业务消息。
 *
 * 校验到「能安全取字段」为止：版本、类型、时间戳、payload 是对象。
 * payload 内部字段由各读取器按需校验，理由同 `protocol.ts`。
 */
export function decodeMessage(
    text: string,
    limitBytes: number = MAX_REMOTE_MESSAGE_BYTES,
): RemoteMessage {
    const bytes = ctx.codec.utf8Encode(text).length;
    if (bytes > limitBytes) {
        throw new ProtocolError('BAD_FRAME', `业务消息 ${bytes} 字节，超过上限 ${limitBytes}`);
    }

    let value: unknown;
    try {
        value = JSON.parse(text);
    } catch {
        throw new ProtocolError('NOT_JSON', '收到非 JSON 的业务消息');
    }
    if (!isObject(value)) throw new ProtocolError('BAD_FRAME', '业务消息不是对象');

    const { v, id, type, timestamp, payload } = value;
    if (v !== PROTOCOL_VERSION) throw new ProtocolError('BAD_FRAME', `协议版本不符：${String(v)}`);
    if (typeof id !== 'string' || id.length === 0) throw new ProtocolError('BAD_FRAME', '业务消息缺少 id');
    if (typeof type !== 'string' || !MESSAGE_TYPE_SET.has(type)) {
        throw new ProtocolError('UNKNOWN_TYPE', `未知业务消息类型：${String(type)}`);
    }
    if (typeof timestamp !== 'number' || !Number.isInteger(timestamp) || timestamp <= 0) {
        throw new ProtocolError('BAD_FRAME', '业务消息时间戳非法');
    }
    if (!isObject(payload)) throw new ProtocolError('BAD_FRAME', `业务消息 ${type} 的 payload 不是对象`);

    return { v, id, type: type as MessageType, timestamp, payload };
}

// ---------------------------------------------------------------------------
// payload 读取器
// ---------------------------------------------------------------------------

function requireString(source: Record<string, unknown>, key: string, message: string): string {
    const value = source[key];
    if (typeof value !== 'string' || value.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', `${message} 缺少字符串字段 ${key}`);
    }
    return value;
}

function optionalInteger(source: Record<string, unknown>, key: string): number | undefined {
    const value = source[key];
    return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : undefined;
}

function optionalString(source: Record<string, unknown>, key: string): string | undefined {
    const value = source[key];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export function readRpcRequest(payload: Record<string, unknown>): RpcRequestPayload {
    return {
        method: requireString(payload, 'method', 'rpc.request'),
        params: payload.params,
    };
}

export function readRpcResponse(payload: Record<string, unknown>): RpcResponsePayload {
    return {
        requestId: requireString(payload, 'requestId', 'rpc.response'),
        result: payload.result,
    };
}

/** `rpc.error`：错误码保留服务端原值，只看 HTTP 状态码分不出该重登还是不重试 */
export function readRpcError(payload: Record<string, unknown>): RpcErrorPayload {
    return {
        requestId: requireString(payload, 'requestId', 'rpc.error'),
        code: requireString(payload, 'code', 'rpc.error'),
        message: requireString(payload, 'message', 'rpc.error'),
        retryable: payload.retryable === true,
        details: payload.details,
    };
}

/** `event`：事件名不做白名单，未知事件由调用方忽略 */
export function readEvent(payload: Record<string, unknown>): EventPayload {
    return {
        event: requireString(payload, 'event', 'event'),
        data: payload.data,
        seq: optionalInteger(payload, 'seq'),
        sessionId: optionalString(payload, 'sessionId'),
    };
}

// ---------------------------------------------------------------------------
// 入站分发
// ---------------------------------------------------------------------------

/**
 * 入站消息的分类结果。
 *
 * 这是「编解码」与「业务处理」的边界：本模块只负责把文本变成带类型的分支，
 * 谁去落地 Promise、谁去推进流式回复由上层决定（首期在 6.6 的拉取循环里）。
 */
export type InboundMessage =
    | { kind: 'response'; requestId: string; result: unknown }
    | { kind: 'error'; requestId: string; code: string; message: string; retryable: boolean; details?: unknown }
    | { kind: 'event'; event: string; data: unknown; seq?: number; sessionId?: string }
    | { kind: 'request'; method: string; params: unknown };

/**
 * 解析并分类一条入站业务消息。
 *
 * `rpc.request` 也解出来：客户端当前不提供服务端方法，
 * 但把「对端在反向调用」显式暴露出来，比按未知类型静默丢弃更容易排查。
 */
export function readInbound(text: string, limitBytes: number = MAX_REMOTE_MESSAGE_BYTES): InboundMessage {
    const message = decodeMessage(text, limitBytes);
    const payload = message.payload as Record<string, unknown>;
    switch (message.type) {
        case 'rpc.response': {
            const response = readRpcResponse(payload);
            return { kind: 'response', requestId: response.requestId, result: response.result };
        }
        case 'rpc.error': {
            const error = readRpcError(payload);
            return {
                kind: 'error',
                requestId: error.requestId,
                code: error.code,
                message: error.message,
                retryable: error.retryable,
                details: error.details,
            };
        }
        case 'event': {
            const event = readEvent(payload);
            return {
                kind: 'event',
                event: event.event,
                data: event.data,
                seq: event.seq,
                sessionId: event.sessionId,
            };
        }
        case 'rpc.request': {
            const request = readRpcRequest(payload);
            return { kind: 'request', method: request.method, params: request.params };
        }
    }
}

// ---------------------------------------------------------------------------
// 挂起请求表
// ---------------------------------------------------------------------------

export interface PendingCall {
    method: string;
    /** 登记时刻，供调用方按自己的策略判断超时（QuickJS 没有 `setTimeout`） */
    since: number;
}

/**
 * RPC 请求 id → 方法名。
 *
 * 只做关联，不持有回调：宿主函数模型决定了回调传不进 QuickJS，
 * 落地 Promise 的时机由拉取循环控制（与控制帧拉取同构）。
 *
 * 超时不在这里实现：QuickJS 没有定时器，且「多久算超时」属于业务策略
 * （普通调用 30 秒、流式打开更长），交给调用方按 `since` 判断。
 */
export class RpcPending {
    private readonly calls = new Map<string, PendingCall>();

    get size(): number {
        return this.calls.size;
    }

    /** 登记一条请求。id 重复说明 id 生成出了问题，直接抛 */
    track(id: string, method: string): void {
        if (this.calls.has(id)) {
            throw new ProtocolError('BAD_PAYLOAD', `RPC 请求 id 重复：${id}`);
        }
        this.calls.set(id, { method, since: Date.now() });
    }

    has(id: string): boolean {
        return this.calls.has(id);
    }

    /** 摘取并移除；对不认识的 id（已超时/已断开）返回 undefined */
    settle(id: string): PendingCall | undefined {
        const call = this.calls.get(id);
        if (call === undefined) return undefined;
        this.calls.delete(id);
        return call;
    }

    /** 清空并返回被丢弃的条数。断线或关闭时调用，避免旧请求永远挂起 */
    dropAll(): number {
        const count = this.calls.size;
        this.calls.clear();
        return count;
    }

    /** 当前在途请求 id，断线恢复时用来逐条标记结果未知 */
    ids(): string[] {
        return Array.from(this.calls.keys());
    }
}
