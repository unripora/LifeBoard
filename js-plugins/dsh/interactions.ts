// 交互事件：审批请求与用户问答。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/client-core/src/harness-alpha-client.ts` §`routeEventFrame` /
//       §`routeWaterfall` / §`respondApproval` / §`respondQuestion`、
//       `packages/client-core/src/harness-questions.ts`、
//       `packages/app-core/src/types.ts` §`AskUserQuestionItem`、`docs/protocol.md` §20
//
// ## 数据面
//
// 审批与问答不走 `session/follow`，而是 Host 的 `$events` 流：
//   - `{type:'ready', clientId}`                       先拿到 clientId，后续回复必须带上
//   - `{type:'waterfall', event, eventId, agentId, request}`  需要用户决定的请求
//   - `{type:'cancel', eventId}`                        Host 撤回了这条请求
//
// 用户做出选择后，调 `$events/result`（仍是 `harness.remote.call`）：
// `{clientId, eventId, outcome}`，`outcome` 为 `{kind:'result', value}` 或
// `{kind:'rejected', error}`。
//
// ## 首期只做「前台请求」
//
// 上游的定时问答还有 `userQuestions/attachWait` + `userQuestions/answer`
// 这套「已转后台后继续回答」的路径，以及基于投影的恢复。首期只处理
// `$events` 上仍挂在前台的请求：连接断了就等重连后 Host 重新发布，
// 不在本地维持第二套问题状态机。
//
// ## 审批的取值
//
// `approval/request` 的回复值只有两种：`allowed-once` / `rejected`。
// 没有「一直允许」——Host 的权限模型不在客户端扩展。

/// <reference path="../types/index.d.ts" />

import { createRpcRequest, type RemoteMessage } from './messages.js';
import { ProtocolError } from './protocol.js';
import { createStreamOpenRequest } from './stream.js';

/** Host 事件流 endpoint */
export const EVENTS_ENDPOINT = '$events';

/** 回答审批/问答的 unary endpoint */
export const EVENTS_RESULT_ENDPOINT = '$events/result';

export type ApprovalOutcome = 'allowed-once' | 'rejected';

export interface QuestionOption {
    label: string;
    description?: string;
}

/** 单个问题，字段与上游 `AskUserQuestionItem` 对齐 */
export interface QuestionItem {
    id: string;
    question: string;
    detail?: string;
    header?: string;
    options?: QuestionOption[];
    multiSelect?: boolean;
}

export interface QuestionAnswer {
    id: string;
    selected: string[];
    custom?: string;
}

export interface ApprovalRequest {
    kind: 'approval';
    eventId: string;
    sessionId: string;
    toolName: string;
    callId?: string;
    reason?: string;
}

export interface QuestionRequest {
    kind: 'question';
    eventId: string;
    sessionId: string;
    questions: QuestionItem[];
}

/**
 * `$events` 帧的分支结果。
 *
 * `ready` 单独一支：没有 clientId 时任何回复都会被 Host 拒绝，
 * 把它显式暴露出来，调用方才能在收到前禁用审批按钮。
 */
export type EventsSignal =
    | { kind: 'ready'; clientId: string }
    | ApprovalRequest
    | QuestionRequest
    | { kind: 'cancel'; eventId: string };

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireString(source: Record<string, unknown>, key: string, message: string): string {
    const value = source[key];
    if (typeof value !== 'string' || value.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', `${message} 缺少字符串字段 ${key}`);
    }
    return value;
}

function optionalString(source: Record<string, unknown>, key: string): string | undefined {
    const value = source[key];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
}

// ---------------------------------------------------------------------------
// 打开事件流
// ---------------------------------------------------------------------------

/** 打开 Host 的 `$events` 流；args 为空对象，Host 主动推送后续帧 */
export function createEventsStreamRequest(streamId: string): RemoteMessage {
    return createStreamOpenRequest(streamId, EVENTS_ENDPOINT, {});
}

// ---------------------------------------------------------------------------
// 帧解析
// ---------------------------------------------------------------------------

/**
 * 解析一条 `$events` 帧。
 *
 * 未知类型（`emit` 等转发事件）返回 `undefined`：它们属于通用事件通道，
 * 首期不消费，但也不该让整条事件流报错。
 */
export function parseEventsFrame(value: unknown): EventsSignal | undefined {
    if (!isObject(value) || typeof value.type !== 'string') {
        throw new ProtocolError('BAD_PAYLOAD', '$events 帧不是对象或缺少 type');
    }
    if (value.type === 'ready') {
        return { kind: 'ready', clientId: requireString(value, 'clientId', 'ready') };
    }
    if (value.type === 'cancel') {
        return { kind: 'cancel', eventId: requireString(value, 'eventId', 'cancel') };
    }
    if (value.type !== 'waterfall') return undefined;

    const event = value.event;
    if (event !== 'approval/request' && event !== 'user-questions/request') return undefined;

    const eventId = requireString(value, 'eventId', 'waterfall');
    const sessionId = requireString(value, 'agentId', 'waterfall');
    const request = value.request;
    if (!isObject(request)) throw new ProtocolError('BAD_PAYLOAD', 'waterfall 缺少 request 对象');

    if (event === 'approval/request') {
        const approval: ApprovalRequest = {
            kind: 'approval',
            eventId,
            sessionId,
            toolName: optionalString(request, 'toolName') ?? 'Harness tool',
        };
        const callId = optionalString(request, 'callId');
        const reason = optionalString(request, 'reason');
        if (callId !== undefined) approval.callId = callId;
        if (reason !== undefined) approval.reason = reason;
        return approval;
    }

    return {
        kind: 'question',
        eventId,
        sessionId,
        questions: readQuestions(request.questions),
    };
}

/** 逐条校验问题，坏条目丢弃；缺失的 questions 视为空数组 */
function readQuestions(value: unknown): QuestionItem[] {
    if (!Array.isArray(value)) return [];
    const questions: QuestionItem[] = [];
    for (const item of value) {
        const question = readQuestion(item);
        if (question !== undefined) questions.push(question);
    }
    return questions;
}

function readQuestion(value: unknown): QuestionItem | undefined {
    if (!isObject(value)) return undefined;
    const id = optionalString(value, 'id');
    const text = optionalString(value, 'question');
    if (id === undefined || text === undefined) return undefined;

    const question: QuestionItem = { id, question: text };
    const detail = optionalString(value, 'detail');
    const header = optionalString(value, 'header');
    if (detail !== undefined) question.detail = detail;
    if (header !== undefined) question.header = header;
    if (value.multiSelect === true) question.multiSelect = true;
    if (Array.isArray(value.options)) {
        const options: QuestionOption[] = [];
        for (const option of value.options) {
            if (!isObject(option)) continue;
            const label = optionalString(option, 'label');
            if (label === undefined) continue;
            const description = optionalString(option, 'description');
            options.push(description === undefined ? { label } : { label, description });
        }
        question.options = options;
    }
    return question;
}

// ---------------------------------------------------------------------------
// 回复构造
// ---------------------------------------------------------------------------

function createResultRequest(
    clientId: string,
    eventId: string,
    outcome: unknown,
): RemoteMessage {
    if (typeof clientId !== 'string' || clientId.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', '$events 尚未 ready，缺少 clientId');
    }
    if (typeof eventId !== 'string' || eventId.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', 'eventId 不能为空');
    }
    return createRpcRequest('harness.remote.call', {
        endpoint: EVENTS_RESULT_ENDPOINT,
        payload: { args: { clientId, eventId, outcome } },
    });
}

/** 审批结果：只允许 `allowed-once` 或 `rejected` */
export function createApprovalResultRequest(
    clientId: string,
    eventId: string,
    outcome: ApprovalOutcome,
): RemoteMessage {
    if (outcome !== 'allowed-once' && outcome !== 'rejected') {
        throw new ProtocolError('BAD_PAYLOAD', `未知审批结果：${String(outcome)}`);
    }
    return createResultRequest(clientId, eventId, { kind: 'result', value: outcome });
}

/** 问答结果：`value` 为 `{ answers }`，与上游 `HarnessQuestionAnswer` 一致 */
export function createQuestionResultRequest(
    clientId: string,
    eventId: string,
    answers: QuestionAnswer[],
): RemoteMessage {
    const cleaned = readAnswers(answers);
    return createResultRequest(clientId, eventId, { kind: 'result', value: { answers: cleaned } });
}

/**
 * 拒绝一条请求。
 *
 * 用于本地无法处理的情况（问题认领失败、答案窗口结束）。
 * Host 用 `code` 区分原因，因此不能传空串。
 */
export function createRejectedResultRequest(
    clientId: string,
    eventId: string,
    code: string,
    message: string,
    name = 'UserQuestionError',
): RemoteMessage {
    if (typeof code !== 'string' || code.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', '拒绝原因 code 不能为空');
    }
    if (typeof message !== 'string' || message.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', '拒绝原因 message 不能为空');
    }
    return createResultRequest(clientId, eventId, {
        kind: 'rejected',
        error: { name, code, message },
    });
}

function readAnswers(answers: QuestionAnswer[]): QuestionAnswer[] {
    if (!Array.isArray(answers) || answers.length === 0) {
        throw new ProtocolError('BAD_PAYLOAD', '问答回复至少需要一条答案');
    }
    return answers.map((answer) => {
        if (!isObject(answer)) throw new ProtocolError('BAD_PAYLOAD', '答案不是对象');
        const id = answer.id;
        if (typeof id !== 'string' || id.length === 0) {
            throw new ProtocolError('BAD_PAYLOAD', '答案缺少问题 id');
        }
        const selected = answer.selected;
        if (!Array.isArray(selected) || selected.some((item) => typeof item !== 'string')) {
            throw new ProtocolError('BAD_PAYLOAD', `问题 ${id} 的 selected 必须是字符串数组`);
        }
        const cleaned: QuestionAnswer = { id, selected: selected.slice() };
        if (typeof answer.custom === 'string' && answer.custom.length > 0) {
            cleaned.custom = answer.custom;
        }
        return cleaned;
    });
}
