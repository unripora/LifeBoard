// 会话（session）列表与新建。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/client-core/src/harness-alpha-client.ts` §`sessionList` / §`sessionCreate`、
//       §`HarnessRemoteSession`、§`normalizeSession`
//
// ## 调用链
//
// LifeBoard → RPC `harness.remote.call`，params 为
// `{ endpoint: 'session/list', payload: { args: { _request: {} } } }`。
// Host 的返回可能再包一层 `{ok:true,value}`，用 `unwrapRemoteResult` 拆掉。
//
// ## 为什么把 args 嵌在 payload.args 里
//
// 这是上游 Typert Gateway 的线格式：`gateway.call(endpoint, { args })`。
// 少嵌一层不会报错，只会得到一个「找不到参数」的 Host 错误，很难看出原因，
// 所以这里由构造器统一保证层级，调用方不用记。
//
// ## 容错策略
//
// 列表逐条校验，坏的丢掉、好的保留（上游 `normalizeSession` 返回 undefined 同义）；
// 新建失败则必须报错——没有 sessionId 后续一切操作都没有目标。

/// <reference path="../types/index.d.ts" />

import { createRpcRequest, type RemoteMessage } from './messages.js';
import { newFrameId, ProtocolError } from './protocol.js';

/** 所有业务 RPC 共用同一个方法名，具体操作由 `endpoint` 区分 */
export const REMOTE_CALL_METHOD = 'harness.remote.call';

/** 发送一条用户消息 */
export const SESSION_PROMPT_ENDPOINT = 'session/prompt';

/** 会话摘要，只保留界面要用的字段 */
export interface RemoteSession {
    sessionId: string;
    /** 毫秒时间戳，用于列表排序 */
    updatedAt: number;
    running: boolean;
    /** 尚未产生任何消息的空会话 */
    blank: boolean;
    /** 缺省标题（会话未命名时界面显示「新会话」） */
    title?: string;
    parentSessionId?: string;
    cwd?: string;
}

export interface SessionCreateTarget {
    /** 优先于 `cwd`；二者都给时只取它，与上游一致 */
    workspaceId?: string;
    cwd?: string;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 组装一次 `harness.remote.call`。args 会按 Typert 线格式嵌到 `payload.args` */
function remoteCall(endpoint: string, args: Record<string, unknown>): RemoteMessage {
    return createRpcRequest(REMOTE_CALL_METHOD, { endpoint, payload: { args } });
}

/** 拉会话列表。`_request` 是上游要求存在的空壳参数 */
export function createSessionListRequest(): RemoteMessage {
    return remoteCall('session/list', { _request: {} });
}

/**
 * 新建会话。
 *
 * 不传目标时交给 Host 决定（通常是 Host 的默认工作区）。
 */
export function createSessionCreateRequest(target: SessionCreateTarget = {}): RemoteMessage {
    const request: Record<string, unknown> = {};
    if (target.workspaceId !== undefined) {
        request.workspaceId = target.workspaceId;
    } else if (target.cwd !== undefined) {
        request.cwd = target.cwd;
    }
    return remoteCall('session/create', { request });
}

export interface PromptOptions {
    /** 幂等去重用；缺省时本地生成。重试同一 prompt 必须复用同一个值 */
    requestId?: string;
    /** Host 用它在回复里标注时间；缺省由 Host 决定时区 */
    clientTimeZone?: string;
}

/**
 * 发送一条文本消息。
 *
 * `mode` 固定 `queue`：`steer` 是「打断当前回合改口径」，
 * 首期只做顺序对话，避免把排队与转向两套状态机一起引入。
 * 文本为空直接报错——当前没有图片/文件，空 prompt 只会得到无意义的空回合。
 */
export function createSessionPromptRequest(
    sessionId: string,
    text: string,
    options: PromptOptions = {},
): RemoteMessage {
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'sessionId 不能为空');
    }
    if (typeof text !== 'string' || text.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'prompt 文本不能为空');
    }
    const request: Record<string, unknown> = {
        requestId: options.requestId ?? newFrameId(),
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text }],
    };
    if (options.clientTimeZone !== undefined) {
        request.clientTimeZone = options.clientTimeZone;
    }
    return remoteCall(SESSION_PROMPT_ENDPOINT, { request });
}

/**
 * 解析 `session/list` 的业务值。
 *
 * 非数组 `items` 视为空列表而不是抛错：Host 版本差异不该让整个会话页白屏。
 */
export function readSessionList(value: unknown): RemoteSession[] {
    if (!isObject(value) || !Array.isArray(value.items)) return [];
    const sessions: RemoteSession[] = [];
    for (const item of value.items) {
        const session = readSession(item);
        if (session !== undefined) sessions.push(session);
    }
    return sessions;
}

/**
 * 校验单条会话。必需字段缺失返回 undefined（丢弃），字段类型不对也丢弃。
 *
 * 标题回退顺序与上游一致：顶层 `title` → `projections.values.title`。
 */
export function readSession(value: unknown): RemoteSession | undefined {
    if (!isObject(value)) return undefined;
    const { sessionId, updatedAt, running, blank } = value;
    if (typeof sessionId !== 'string' || sessionId.length === 0) return undefined;
    if (typeof updatedAt !== 'number' || !Number.isFinite(updatedAt)) return undefined;
    if (typeof running !== 'boolean' || typeof blank !== 'boolean') return undefined;

    const session: RemoteSession = { sessionId, updatedAt, running, blank };
    const title = readTitle(value);
    if (title !== undefined) session.title = title;
    if (typeof value.parentSessionId === 'string' && value.parentSessionId.length > 0) {
        session.parentSessionId = value.parentSessionId;
    }
    if (typeof value.cwd === 'string' && value.cwd.length > 0) session.cwd = value.cwd;
    return session;
}

function readTitle(value: Record<string, unknown>): string | undefined {
    if (typeof value.title === 'string' && value.title.length > 0) return value.title;
    const projections = value.projections;
    if (!isObject(projections) || !isObject(projections.values)) return undefined;
    const projected = projections.values.title;
    return typeof projected === 'string' && projected.length > 0 ? projected : undefined;
}

/** 解析 `session/create` 的业务值，返回新会话 id */
export function readSessionCreateResult(value: unknown): string {
    const sessionId = isObject(value) ? value.sessionId : undefined;
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'session/create 未返回 sessionId');
    }
    return sessionId;
}
