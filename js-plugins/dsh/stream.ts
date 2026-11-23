// 流式通道：打开/关闭远端 stream、路由事件帧、聚合助手增量文本。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/client-core/src/remote-gateway.ts` §`open` / §`routeStreamEvent`、
//       `packages/client-core/src/harness-alpha-client.ts` §`pumpSessionFollow` /
//       §`entriesFromSessionFollow`、`packages/protocol/src/index.ts`
//       §`HarnessRemoteFrameData` / §`HarnessRemoteStreamClosedData`、`docs/protocol.md` §20
//
// ## 与控制帧、业务消息的区别
//
// 控制帧是 WS 上明文中转的 18 类帧；业务消息是加密后的 RPC/Event。
// 这里处理第三层：`harness.remote.stream.open` 打开一条远端流后，
// 实际内容通过 **业务事件** 逐条推回：
//   - `harness.remote.frame`           一条流数据（`{streamId, hasValue, value}`）
//   - `harness.remote.stream.closed`   流的终止（`{streamId, reason, failure?}`）
//
// 因此本模块只做两件事：构造开/关请求（含 Typert 线格式的嵌套），
// 以及把事件数据变成强类型分支 + 把助手增量拼成文本。
//
// ## 为什么不用 transferFrames
//
// `stream.transfer.v1` 需要先协商能力，并在单个 item 超过 4 MiB 时改用
// `harness.remote.transfer.read/close` 分块读取。首期只做文本对话，
// 单条增量远小于上限，因此不申请该能力；收到 transfer 描述帧直接报错，
// 避免「以为拿到了内容，其实只拿到描述」的静默错误。
//
// ## 为什么聚合器忽略 snapshot
//
// `session/follow` 首帧是历史快照，里面的助手增量属于「已经发生过的消息」，
// 界面会通过会话历史单独渲染。聚合器只从快照之后的新增量开始拼，
// 否则同一条回复会在历史与流式区各出现一次。

/// <reference path="../types/index.d.ts" />

import { createRpcRequest, type RemoteMessage } from './messages.js';
import { ProtocolError } from './protocol.js';

/** 打开远端流的方法名 */
export const STREAM_OPEN_METHOD = 'harness.remote.stream.open';

/** 关闭远端流的方法名 */
export const STREAM_CLOSE_METHOD = 'harness.remote.stream.close';

/** 会话跟随流：先回历史快照，再持续推送新事件 */
export const SESSION_FOLLOW_ENDPOINT = 'session/follow';

/** 单条 stream 数据事件 */
export const EVENT_FRAME = 'harness.remote.frame';

/** stream 终止事件 */
export const EVENT_STREAM_CLOSED = 'harness.remote.stream.closed';

/**
 * `session/follow` 首次快照携带的最大消息数。
 *
 * 取上游默认值 60：够填满一屏历史，又不会让首帧在弱网下过慢。
 */
export const MAX_SESSION_FOLLOW_MESSAGES = 60;

/** 流的终止原因，与上游一致 */
export const STREAM_CLOSE_REASONS = [
    'cancelled',
    'completed',
    'failed',
    'peer-disconnected',
] as const;

export type StreamCloseReason = typeof STREAM_CLOSE_REASONS[number];

const CLOSE_REASON_SET = new Set<string>(STREAM_CLOSE_REASONS);

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireNonEmpty(value: unknown, name: string): string {
    if (typeof value !== 'string' || value.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', `${name} 不能为空`);
    }
    return value;
}

// ---------------------------------------------------------------------------
// 请求构造
// ---------------------------------------------------------------------------

/**
 * 打开一条远端流。
 *
 * `payload` 与 `harness.remote.call` 同形：`{ args }`，由调用方按 endpoint
 * 自己决定 request 结构。少嵌一层只会得到 Host 的「参数缺失」，联调时很难定位。
 */
export function createStreamOpenRequest(
    streamId: string,
    endpoint: string,
    args: Record<string, unknown>,
): RemoteMessage {
    requireNonEmpty(streamId, 'streamId');
    requireNonEmpty(endpoint, 'endpoint');
    return createRpcRequest(STREAM_OPEN_METHOD, {
        streamId,
        endpoint,
        payload: { args },
    });
}

/**
 * 跟随一个普通会话。
 *
 * 地址固定为 `{ kind: 'session', sessionId }`：子代理会话要走
 * `{ kind: 'subagent', parentSessionId, childSessionId, mode }`，
 * 首期不接子代理，避免地址降级绕过 Host 的 origin 校验。
 */
export function createSessionFollowRequest(
    streamId: string,
    sessionId: string,
    maxMessages: number = MAX_SESSION_FOLLOW_MESSAGES,
): RemoteMessage {
    requireNonEmpty(sessionId, 'sessionId');
    if (!Number.isInteger(maxMessages) || maxMessages <= 0) {
        throw new ProtocolError('BAD_PAYLOAD', `maxMessages 必须是正整数：${String(maxMessages)}`);
    }
    return createStreamOpenRequest(streamId, SESSION_FOLLOW_ENDPOINT, {
        request: {
            address: { kind: 'session', sessionId },
            maxMessages,
        },
    });
}

/**
 * 关闭一条远端流。
 *
 * 打开失败也要调用：Host 在 open 的 RPC 返回前就登记了流槽位，
 * 不关会一直占着「每连接 16 条」的限额。
 */
export function createStreamCloseRequest(streamId: string): RemoteMessage {
    return createRpcRequest(STREAM_CLOSE_METHOD, { streamId: requireNonEmpty(streamId, 'streamId') });
}

// ---------------------------------------------------------------------------
// 事件解析
// ---------------------------------------------------------------------------

export interface StreamFailure {
    code: string;
    message: string;
    details: Record<string, unknown>;
}

/**
 * 一条流事件的分支结果。
 *
 * 与事件分发解耦：本模块不碰网络与状态，调用方拿到分支后自己决定
 * 是喂给聚合器、结束 UI，还是按失败码提示用户。
 */
export type StreamSignal =
    | { kind: 'frame'; streamId: string; hasValue: boolean; value: unknown }
    | { kind: 'closed'; streamId: string; reason: StreamCloseReason; failure?: StreamFailure };

/**
 * 把业务事件解析为流信号。
 *
 * 未知事件名返回 `undefined` 而不是抛错：事件集合会随协议演进增长，
 * 多一个无关事件不该让整条会话中断——与控制帧「未知类型忽略」的取舍一致。
 */
export function parseStreamEvent(event: string, data: unknown): StreamSignal | undefined {
    if (event !== EVENT_FRAME && event !== EVENT_STREAM_CLOSED) return undefined;
    if (!isObject(data)) {
        throw new ProtocolError('BAD_PAYLOAD', `${event} 的 data 不是对象`);
    }
    const streamId = requireNonEmpty(data.streamId, 'streamId');

    if (event === EVENT_FRAME) {
        // 未申请 transferFrames，收到描述帧说明两端能力判断出了偏差
        if (data.transfer !== undefined) {
            throw new ProtocolError('BAD_FRAME', '收到未启用的 stream transfer 描述帧');
        }
        return {
            kind: 'frame',
            streamId,
            hasValue: data.hasValue === true,
            value: data.value,
        };
    }

    const reason = data.reason;
    if (typeof reason !== 'string' || !CLOSE_REASON_SET.has(reason)) {
        throw new ProtocolError('BAD_PAYLOAD', `未知的流终止原因：${String(reason)}`);
    }
    const signal: StreamSignal = {
        kind: 'closed',
        streamId,
        reason: reason as StreamCloseReason,
    };
    if (reason === 'failed' && isObject(data.failure)) {
        signal.failure = {
            code: typeof data.failure.code === 'string' && data.failure.code.length > 0
                ? data.failure.code
                : 'internal',
            message: typeof data.failure.message === 'string' && data.failure.message.length > 0
                ? data.failure.message
                : '远端流失败',
            details: isObject(data.failure.details) ? data.failure.details : {},
        };
    }
    return signal;
}

// ---------------------------------------------------------------------------
// 助手增量聚合
// ---------------------------------------------------------------------------

/**
 * 把 `assistant/chunk` 增量拼成文本。
 *
 * 只关心文本与推理两路 delta：`block-start` / `block-end` / `tool-call-delta`
 * 首期不渲染，忽略即可。忽略而不是报错，是因为它们出现只说明对方在调用工具，
 * 并不表示协议不兼容。
 */
export class AssistantStreamAggregator {
    private textValue = '';
    private reasoningValue = '';
    private applied = 0;

    /** 已拼接的正文 */
    get text(): string {
        return this.textValue;
    }

    /** 已拼接的推理内容（与正文分开，界面可折叠展示） */
    get reasoning(): string {
        return this.reasoningValue;
    }

    /** 实际生效的增量条数，用于判断流是否真的在推进 */
    get chunkCount(): number {
        return this.applied;
    }

    /** 清空，用于开始新一轮回复 */
    reset(): void {
        this.textValue = '';
        this.reasoningValue = '';
        this.applied = 0;
    }

    /**
     * 输入一条 `session/follow` 的 item。
     *
     * 同时兼容两种形态：
     * - 普通历史项：`{ type: 'event', event: { type: 'assistant/chunk', data: { chunk } } }`
     * - 流式加速帧：`{ type: 'assistant-stream', frame: { type: 'chunk', chunk } }`
     *
     * @returns 是否产生了新的文本增量
     */
    pushItem(value: unknown): boolean {
        if (!isObject(value)) return false;
        if (value.type === 'assistant-stream') {
            const frame = value.frame;
            if (!isObject(frame) || frame.type !== 'chunk') return false;
            return this.applyChunk(frame.chunk);
        }
        if (value.type !== 'event' || !isObject(value.event)) return false;
        const event = value.event;
        if (event.type !== 'assistant/chunk' || !isObject(event.data)) return false;
        return this.applyChunk(event.data.chunk);
    }

    /** 应用单个 chunk；非文本增量返回 false */
    applyChunk(chunk: unknown): boolean {
        if (!isObject(chunk) || typeof chunk.type !== 'string') return false;
        if (typeof chunk.text !== 'string') return false;
        if (chunk.type === 'text-delta') {
            this.textValue += chunk.text;
            this.applied += 1;
            return true;
        }
        if (chunk.type === 'reasoning-delta') {
            this.reasoningValue += chunk.text;
            this.applied += 1;
            return true;
        }
        return false;
    }
}
