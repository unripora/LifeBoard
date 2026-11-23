// 会话编排：把 M6 的零件串成「一个会话正在发生什么」。
//
// 依赖：`messages.ts`（RPC 挂起表与入站分类）、`stream.ts`（流信号与增量聚合）、
//       `interactions.ts`（审批/问答帧）、`recovery.ts`（断线恢复计划）。
//
// ## 为什么需要这一层
//
// 前六个模块各自只回答「这类字节是什么意思」；界面需要的是另一个问题：
// 「当前会话的回复在哪、能不能发下一条、有没有待审批」。
// 本模块持有这组状态，并把网络输入翻译成回调，避免每个页面各写一份状态机。
//
// ## 不做的事
//
// - 不发起连接、不读 WS：宿主把文本喂进 `handleMessage` 即可，便于离线自测。
// - 不渲染 UI：只通过 `handlers` 通知「文本变了 / 来了审批 / 请求失败」。
// - 不重试：断线时 `disconnect()` 返回恢复计划，重连与等待由上层执行。

/// <reference path="../types/index.d.ts" />

import {
    readInbound,
    type RemoteMessage,
    RpcPending,
} from './messages.js';
import { createSessionPromptRequest } from './sessions.js';
import {
    AssistantStreamAggregator,
    createSessionFollowRequest,
    parseStreamEvent,
    type StreamCloseReason,
    type StreamFailure,
} from './stream.js';
import {
    createApprovalResultRequest,
    createEventsStreamRequest,
    createQuestionResultRequest,
    createRejectedResultRequest,
    parseEventsFrame,
    type ApprovalOutcome,
    type ApprovalRequest,
    type QuestionAnswer,
    type QuestionRequest,
} from './interactions.js';
import { planRecovery, type RecoveryAction } from './recovery.js';
import { ProtocolError } from './protocol.js';

export interface ConversationHandlers {
    /** 聚合文本有增长时触发；参数是累计值，不是增量 */
    onText?(text: string, reasoning: string): void;
    /** `$events` ready；此后审批/问答才能回复 */
    onClientReady?(clientId: string): void;
    onApproval?(request: ApprovalRequest): void;
    onQuestion?(request: QuestionRequest): void;
    /** Host 撤回了审批或问答 */
    onInteractionCancelled?(eventId: string): void;
    /** RPC 明确失败（含 Host 业务拒绝） */
    onRequestFailed?(requestId: string, code: string, message: string): void;
    /** 会话跟随流结束或失败 */
    onStreamClosed?(reason: StreamCloseReason, failure?: StreamFailure): void;
}

export type PendingInteraction = ApprovalRequest | QuestionRequest;

/**
 * 一个会话的编排器。
 *
 * 生命周期：`attachSession` → `openEvents` → 循环 `handleMessage` →
 * 断线时 `disconnect` → 重连后重新 `attachSession` / `openEvents`。
 */
export class ConversationController {
    /** 增量聚合器，界面可直接读 `text` / `reasoning` */
    readonly aggregator = new AssistantStreamAggregator();
    /** RPC 请求关联表 */
    readonly pending = new RpcPending();

    private sessionValue?: string;
    private followStreamId?: string;
    private eventsStreamId?: string;
    private clientIdValue?: string;
    private readonly promptIds = new Set<string>();
    private readonly interactions = new Map<string, PendingInteraction>();

    constructor(private readonly handlers: ConversationHandlers = {}) {}

    get sessionId(): string | undefined {
        return this.sessionValue;
    }

    get clientId(): string | undefined {
        return this.clientIdValue;
    }

    /** 是否还有未返回的 prompt（含排队中的） */
    get streaming(): boolean {
        return this.promptIds.size > 0;
    }

    get pendingInteractions(): PendingInteraction[] {
        return Array.from(this.interactions.values());
    }

    /**
     * 切换到某个会话并开始跟随。
     *
     * 每次调用都重置聚合文本：历史快照与旧会话的增量混在一起，
     * 界面会显示两段对话。
     */
    attachSession(sessionId: string, streamId: string): RemoteMessage {
        const request = createSessionFollowRequest(streamId, sessionId);
        this.sessionValue = sessionId;
        this.followStreamId = streamId;
        this.aggregator.reset();
        return request;
    }

    /** 打开 Host 的审批/问答事件流 */
    openEvents(streamId: string): RemoteMessage {
        this.eventsStreamId = streamId;
        return createEventsStreamRequest(streamId);
    }

    /**
     * 发送一条 Prompt。
     *
     * 重发同一条消息时必须显式传 `requestId`：Host 用它做幂等去重，
     * 不传会生成新的 id，用户会看到两条一样的提问。
     */
    sendPrompt(text: string, requestId?: string): RemoteMessage {
        if (this.sessionValue === undefined) {
            throw new ProtocolError('BAD_PAYLOAD', '尚未选择会话');
        }
        const message = createSessionPromptRequest(this.sessionValue, text, { requestId });
        this.pending.track(message.id, 'session/prompt');
        this.promptIds.add(message.id);
        return message;
    }

    /** 喂入一条 WS 文本帧（控制帧之外的业务消息） */
    handleMessage(text: string): void {
        const inbound = readInbound(text);
        if (inbound.kind === 'response') {
            this.settle(inbound.requestId);
            return;
        }
        if (inbound.kind === 'error') {
            this.settle(inbound.requestId);
            this.handlers.onRequestFailed?.(inbound.requestId, inbound.code, inbound.message);
            return;
        }
        if (inbound.kind === 'event') {
            const signal = parseStreamEvent(inbound.event, inbound.data);
            if (signal !== undefined) this.handleStreamSignal(signal);
            // 未知事件交给上层忽略：协议会加新事件，不该中断会话
        }
    }

    private settle(requestId: string): void {
        if (this.pending.settle(requestId) === undefined) return;
        this.promptIds.delete(requestId);
    }

    private handleStreamSignal(signal: ReturnType<typeof parseStreamEvent>): void {
        if (signal === undefined) return;
        if (signal.kind === 'closed') {
            if (signal.streamId !== this.followStreamId) return;
            // 流已结束：在途 prompt 不会再有结果，按未知处理而不是继续转圈
            this.promptIds.clear();
            this.handlers.onStreamClosed?.(signal.reason, signal.failure);
            return;
        }

        if (signal.streamId === this.followStreamId) {
            if (!signal.hasValue) return;
            if (this.aggregator.pushItem(signal.value)) {
                this.handlers.onText?.(this.aggregator.text, this.aggregator.reasoning);
            }
            return;
        }
        if (signal.streamId === this.eventsStreamId && signal.hasValue) {
            this.handleEventsFrame(signal.value);
        }
    }

    /** 处理 `$events` 的 ready / waterfall / cancel */
    handleEventsFrame(value: unknown): void {
        const event = parseEventsFrame(value);
        if (event === undefined) return;
        if (event.kind === 'ready') {
            this.clientIdValue = event.clientId;
            this.handlers.onClientReady?.(event.clientId);
            return;
        }
        if (event.kind === 'cancel') {
            this.interactions.delete(event.eventId);
            this.handlers.onInteractionCancelled?.(event.eventId);
            return;
        }
        this.interactions.set(event.eventId, event);
        if (event.kind === 'approval') this.handlers.onApproval?.(event);
        else this.handlers.onQuestion?.(event);
    }

    /** 回复审批；回复后本地不再保留该请求 */
    approve(eventId: string, outcome: ApprovalOutcome): RemoteMessage {
        const request = createApprovalResultRequest(this.requireClientId(), eventId, outcome);
        this.interactions.delete(eventId);
        return request;
    }

    answerQuestion(eventId: string, answers: QuestionAnswer[]): RemoteMessage {
        const request = createQuestionResultRequest(this.requireClientId(), eventId, answers);
        this.interactions.delete(eventId);
        return request;
    }

    /** 本地无法处理时显式拒绝，避免 Host 一直等 */
    rejectInteraction(eventId: string, code: string, message: string): RemoteMessage {
        const request = createRejectedResultRequest(this.requireClientId(), eventId, code, message);
        this.interactions.delete(eventId);
        return request;
    }

    private requireClientId(): string {
        if (this.clientIdValue === undefined) {
            throw new ProtocolError('BAD_PAYLOAD', '$events 尚未 ready，不能回复审批或问答');
        }
        return this.clientIdValue;
    }

    /**
     * 断线处理：生成恢复计划并清空本地瞬态。
     *
     * `clientId` 与流 id 都作废——重连后是新一代连接，旧 id 回复会被 Host 拒绝。
     */
    disconnect(): RecoveryAction[] {
        const plan = planRecovery({
            activeSessionId: this.sessionValue,
            pendingRequestIds: this.pending.ids(),
            pendingApprovalEventIds: Array.from(this.interactions.keys()),
        });
        this.pending.dropAll();
        this.promptIds.clear();
        this.interactions.clear();
        this.clientIdValue = undefined;
        this.followStreamId = undefined;
        this.eventsStreamId = undefined;
        return plan;
    }
}
