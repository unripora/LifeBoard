// 断线重连策略与恢复计划。
//
// 参考：`docs/tasks/feature_remote_agent/R2_review_20261120.md` §恢复语义、
//       `docs/protocol.md` §20（流与事件）、§「恢复」、
//       `packages/client-core/src/remote-gateway.ts`（断线时挂起请求统一失败）
//
// ## 恢复的权威来源是 Host
//
// 本地不建消息镜像库：重连后重新 `session/list` 拿运行状态，
// 重新 `session/follow` 拿历史快照，界面以快照重建。
// 这样不需要在本地维护游标与去重表，也不会出现「本地有一条、Host 没有」的分叉。
//
// ## 结果未知的请求不重放
//
// 断线时仍在途的 RPC（尤其 `session/prompt`）结果未知：
// Host 可能已经执行，也可能没有。重放可能导致重复消息，因此统一按
// `UNKNOWN_OUTCOME` 失败交给界面提示，由用户决定是否重发。
// 重发时必须复用原 `requestId`，Host 才能幂等去重。
//
// ## 审批请求不本地保留
//
// 断线后 Host 会重新发布仍挂起的 `waterfall` 审批/问答。
// 本地旧 eventId 一律作废，避免用上一代的 clientId / eventId 回复被拒。
//
// ## 为什么不在这里做退避循环
//
// QuickJS 没有 `setTimeout`，等待由宿主的常驻泵负责。
// 本模块只算「下一次该等多久」和「重连后该做什么」，
// 不持有定时器、不发起连接，纯函数因此可以离线自检。

/// <reference path="../types/index.d.ts" />

import { ProtocolError } from './protocol.js';

export interface ReconnectPolicy {
    /** 首次重试等待（毫秒） */
    baseDelayMs: number;
    /** 单次等待上限（毫秒） */
    maxDelayMs: number;
    /** 每次失败后的增长倍数 */
    factor: number;
}

/** 默认：0.5s 起、每次翻倍、封顶 30s。弱网下约 6 次后到达上限。 */
export const DEFAULT_RECONNECT_POLICY: ReconnectPolicy = {
    baseDelayMs: 500,
    maxDelayMs: 30_000,
    factor: 2,
};

/**
 * 计算第 `attempt` 次重连前的等待时间。
 *
 * `attempt` 从 1 开始；小于 1 或非整数按 1 处理，避免调用方用 0 得到「立刻重连」。
 */
export function nextReconnectDelay(
    attempt: number,
    policy: ReconnectPolicy = DEFAULT_RECONNECT_POLICY,
): number {
    if (policy.baseDelayMs <= 0 || policy.maxDelayMs <= 0 || policy.factor < 1) {
        throw new ProtocolError('BAD_PAYLOAD', '重连策略参数非法');
    }
    const normalized = Number.isInteger(attempt) && attempt >= 1 ? attempt : 1;
    const raw = policy.baseDelayMs * Math.pow(policy.factor, normalized - 1);
    return Math.min(Math.round(raw), policy.maxDelayMs);
}

/**
 * 连接代次。
 *
 * 每次重连 `begin()` 自增；异步回调带着旧代次回来时用 `isCurrent` 丢弃，
 * 避免上一代连接的响应被当成本代结果（尤其是会话快照）。
 */
export class ConnectionEpoch {
    private value = 0;

    /** 当前代次；尚未连接时为 0 */
    get current(): number {
        return this.value;
    }

    /** 开始新一代连接，返回新代次 */
    begin(): number {
        this.value += 1;
        return this.value;
    }

    /** 回调携带的代次是否仍然有效 */
    isCurrent(epoch: number): boolean {
        return epoch === this.value && epoch > 0;
    }
}

/** 重连后要执行的动作，按数组顺序执行 */
export type RecoveryAction =
    | { kind: 'failPendingRequest'; requestId: string; code: 'UNKNOWN_OUTCOME' }
    | { kind: 'refreshSessions' }
    | { kind: 'reopenFollow'; sessionId: string }
    | { kind: 'reopenEvents' }
    | { kind: 'dropLocalApproval'; eventId: string };

export interface RecoveryState {
    /** 断线时正在查看的会话；没有则只刷新列表 */
    activeSessionId?: string;
    /** 断线时仍在途的 RPC 请求 id */
    pendingRequestIds?: string[];
    /** 断线时界面上仍是「待审批/待回答」的 eventId */
    pendingApprovalEventIds?: string[];
}

/**
 * 生成重连后的恢复动作。
 *
 * 顺序有意为之：先让挂起请求失败（解除界面等待），再刷新列表，
 * 再重建会话流与事件流，最后清掉本地审批残留。
 */
export function planRecovery(state: RecoveryState = {}): RecoveryAction[] {
    const actions: RecoveryAction[] = [];

    for (const requestId of state.pendingRequestIds ?? []) {
        if (typeof requestId === 'string' && requestId.length > 0) {
            actions.push({ kind: 'failPendingRequest', requestId, code: 'UNKNOWN_OUTCOME' });
        }
    }

    actions.push({ kind: 'refreshSessions' });

    if (typeof state.activeSessionId === 'string' && state.activeSessionId.length > 0) {
        actions.push({ kind: 'reopenFollow', sessionId: state.activeSessionId });
    }

    actions.push({ kind: 'reopenEvents' });

    for (const eventId of state.pendingApprovalEventIds ?? []) {
        if (typeof eventId === 'string' && eventId.length > 0) {
            actions.push({ kind: 'dropLocalApproval', eventId });
        }
    }

    return actions;
}
