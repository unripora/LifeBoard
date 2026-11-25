// DSH 会话层的线上调用：列会话、发 prompt、跟随会话流。
//
// 依据：`deepseek-ai/deepseek-harness` 的
//   `packages/api/session-controller/src/types.ts`（请求/响应与 follow 帧）
//   `packages/api/session-controller/src/index.ts`（`@Remote('list')` 等装饰器）
//
// ## 两条通道分工
//
// | 用途 | 通道 | 原因 |
// |---|---|---|
// | 列会话、发 prompt、中断 | `POST /api/session/*` | 一问一答，不必管 streamId |
// | 收消息与流式增量 | WS `session/follow` 流 | 服务端主动推送，必须常驻订阅 |
//
// ## 消息从哪来
//
// `session/follow` 的帧有三种（`SessionFollowFrame`）：
// - `snapshot`：打开时的窗口（历史 + 是否还有更多）
// - `event`：一条新落库事件（`user/message`、`assistant/message`、`tool/result` 等）
// - `assistant-stream`：**流式增量**，`start`/`chunk`/`end` 三段
//
// 正文最终以 `assistant/message` 事件的落库版本为准；`assistant-stream`
// 只是让用户提前看到「正在写」。两者不一致时以事件为准——这与宿主
// 对 `ctx.stream.emit` 的约定一致（增量仅即时展示，权威结果是最终区块）。

/// <reference path="../types/index.d.ts" />

import { MuxConnection } from './mux.js';
import { rpcCall, type RpcOptions } from './rpc.js';

/** 会话列表里的一行（只取界面与路由要用的字段） */
export interface SessionSummary {
    sessionId: string;
    updatedAt: number;
    running: boolean;
    blank: boolean;
    /** 是否有一台在线的 Agent 持有它——决定「能不能发消息」 */
    agentAvailable: boolean;
    cwd?: string;
    parentSessionId?: string;
    /** 子代理会话不该出现在主列表里 */
    origin?: string;
}

/**
 * 列可见会话。
 *
 * 返回按活动时间排序。`agentAvailable` 为 false 的会话只能看历史，
 * 发 prompt 会被服务端拒绝（需要 resume），所以首期只允许选可用会话。
 *
 * ## 为什么是 `_request: {}`
 *
 * Remote 端点的 `args` 里每个键都对应**服务端方法的一个形参名**，不是随便起的。
 * `@Remote('list') list(_request: SessionListRequest, signal)` 的首参就叫
 * `_request`，所以必须送 `{_request: {}}`。少这个键会报
 * `args fields do not match the descriptor: missing "_request"`。
 */
export async function listSessions(options: RpcOptions): Promise<SessionSummary[]> {
    const value = await rpcCall<{ items?: unknown[] }>(options, 'session/list', { _request: {} });
    const items = Array.isArray(value.items) ? value.items : [];
    const out: SessionSummary[] = [];
    for (const raw of items) {
        const row = readSummary(raw);
        // 子代理会话是父会话的实现细节，混进列表会让人以为有多个对话
        if (row !== null && row.origin !== 'subagent') out.push(row);
    }
    return out;
}

function readSummary(raw: unknown): SessionSummary | null {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
    const r = raw as Record<string, unknown>;
    // 没有 sessionId 的行没法路由，丢弃比留一个点不动的条目好
    if (typeof r.sessionId !== 'string' || r.sessionId.length === 0) return null;
    const row: SessionSummary = {
        sessionId: r.sessionId,
        updatedAt: typeof r.updatedAt === 'number' ? r.updatedAt : 0,
        running: r.running === true,
        blank: r.blank === true,
        agentAvailable: r.agentAvailable === true,
    };
    if (typeof r.cwd === 'string' && r.cwd.length > 0) row.cwd = r.cwd;
    if (typeof r.parentSessionId === 'string') row.parentSessionId = r.parentSessionId;
    if (typeof r.origin === 'string') row.origin = r.origin;
    return row;
}

/**
 * 发一条 prompt。
 *
 * ## 请求体的四个字段都必须有
 *
 * `sessionId` 定会话；`mode: 'queue'` 表示「排队执行」（另一选项是 `steer`，
 * 用于打断当前回合插话，首期不用）；`content` 是内容块数组，纯文本就是
 * `[{type:'text', text}]`；`clientTimeZone` 让服务端按用户时区解释时间。
 *
 * ## 重发不安全
 *
 * prompt 会写进会话日志。网络抖动时重发可能让同一条消息出现两次，
 * 所以 `retry: false` 已在 [rpcCall] 里定死。
 *
 * 服务端对同一 `requestId` 的重复提交返回原接受结果（幂等），
 * 因此这里也带上 requestId，方便未来做「重试同一请求」而不重发。
 */
export async function promptSession(
    options: RpcOptions,
    sessionId: string,
    text: string,
    requestId: string,
): Promise<void> {
    // 形参名叫 `request`（`prompt(request: SessionPromptRequest, signal)`），
    // 所以 args 里必须是 `request` 这个键
    await rpcCall<{ accepted: true }>(options, 'session/prompt', {
        request: {
            sessionId,
            mode: 'queue',
            content: [{ type: 'text', text }],
            requestId,
        },
    });
}

// ## 为什么不传 clientTimeZone
//
// 服务端只接受 `UTC` 或合法的 IANA 名称（如 `Asia/Shanghai`），
// 传 `+08:00` 这种偏移量会被 `session/invalid-time-zone` 拒掉。
// 而 QuickJS 没有完整的 `Intl`，拿不到 IANA 名称——`Intl` 可能不存在，
// `resolvedOptions()` 也可能返回 `undefined`。
//
// 好在这个字段是**可选**的：省略时服务端退回它自己的时区。
// 猜一个错的比不传更糟（会把用户消息的时间标到别的时区），所以直接省略。

/**
 * 中断当前回合。
 *
 * 走 `session/control` 之外没有专门的 cancel 端点，DST 的取消语义绑定在
 * 会话的 live 状态上。首期不做中断，留这个注释是为了说明「为什么没有」：
 * 不是漏了，是需要先确认哪个端点承担它。
 */

// ---------------------------------------------------------------------------
// 会话跟随流
// ---------------------------------------------------------------------------

/** 从 follow 流里解析出来的一条消息 */
export interface FollowMessage {
    role: 'user' | 'assistant' | 'system';
    text: string;
}

/** 跟随流的回调 */
export interface FollowHandlers {
    /** 打开时的历史消息（snapshot 帧） */
    onSnapshot(messages: FollowMessage[], running: boolean): void;
    /** 一条新落库的消息 */
    onMessage(message: FollowMessage): void;
    /** 流式增量：助手正在写的片段（**增量**，不是累计） */
    onDelta(text: string): void;
    /** 这一轮结束（以 `turn/end` 为准，见 [handleFollowFrame]） */
    onTurnEnd(): void;
    /**
     * 这一轮以错误结束。
     *
     * 必须单独回调而不是并进 [onTurnEnd]：用户需要看到「为什么没回复」。
     * 实测最常见的形态是模型 provider 返回 503（`xingchi API error (503)`），
     * 服务端会自动重试 5 次，全失败才落到 `turn/end` 的 error 分支。
     */
    onError(message: string): void;
    /** 流失败或被关闭 */
    onClosed(reason: string): void;
}

/**
 * 打开一条 `session/follow` 订阅。
 *
 * 返回的对象用于关闭。调用方必须保证只开一条——多条会收到重复帧，
 * 表现为消息重复出现。
 */
export async function followSession(
    gateway: string,
    sessionId: string,
    handlers: FollowHandlers,
): Promise<{ close(): void }> {
    const mux = await MuxConnection.connect(gateway);
    // `follow(request: SessionFollowRequest, signal)`：形参名 `request`，
    // 其内部直接是 `address`（`SessionAddress` 是平铺对象，没有额外包装）。
    //
    // 两个开关都必须显式给，少一个就「收得到历史、收不到新消息」：
    // - `activate: true`：不传时这是**被动读取**，服务端发完 snapshot 就结束流
    //   （`web/controller.ts:62` 只有它为 true 才 `retain()` 提升成长期订阅）
    // - `assistantStream: true`：不传就只有落库事件，没有逐字增量
    const stream = mux.open('session/follow', {
        args: {
            request: {
                address: { kind: 'session', sessionId },
                activate: true,
                assistantStream: true,
            },
        },
    });
    let closed = false;
    let lastDelta = '';

    void (async () => {
        try {
            // 循环**永不因一轮对话结束而退出**。
            //
            // 这里踩过一个严重的坑：早先版本在「助手消息落库」或
            // `assistant-stream` 的 `end` 时 return，顺手把整条订阅关了。
            // 表现是第一条消息有回复、第二条永远没反应——因为流已经没了。
            // 一轮结束只是「可以恢复输入框」，订阅必须一直活着等下一轮。
            for (;;) {
                const value = await stream.next();
                if (closed) return;
                handleFollowFrame(value, handlers, () => lastDelta, (next) => { lastDelta = next; });
            }
        } catch (error) {
            if (closed) return;
            const reason = error instanceof Error ? error.message : String(error);
            handlers.onClosed(reason);
        } finally {
            mux.close();
        }
    })();

    return {
        close() {
            if (closed) return;
            closed = true;
            stream.cancel();
            mux.close();
        },
    };
}

/**
 * 处理一帧 follow 数据。
 *
 * ## 为什么返回 void 而不是「该不该继续」
 *
 * 早先版本返回 `'stop'` 让调用方结束读取循环，这是个错误设计：
 * 一轮对话结束不等于订阅结束。现在由调用方无条件循环，
 * 只有 `close()` 或连接错误才会停。
 */
function handleFollowFrame(
    value: unknown,
    handlers: FollowHandlers,
    readDelta: () => string,
    writeDelta: (next: string) => void,
): void {
    if (typeof value !== 'object' || value === null) return;
    const frame = value as Record<string, unknown>;

    if (frame.type === 'snapshot') {
        const records = Array.isArray(frame.records) ? frame.records : [];
        const messages: FollowMessage[] = [];
        for (const record of records) {
            const message = readEventMessage(record);
            if (message !== null) messages.push(message);
        }
        handlers.onSnapshot(messages, readRunning(frame));
        return;
    }

    if (frame.type === 'event') {
        const event = readEventObject(frame);
        if (event === null) return;
        // `turn/end` 才是**一轮**的真正结束。
        //
        // 一次 turn 可能经历多次 attempt：provider 报错会自动重试（实测 503
        // 重试 5 次，共 6 次 attempt）。拿 `assistant-stream` 的 `end` 当结束，
        // 会在同一轮里误报 6 次「已完成」，输入框反复闪。
        if (event.type === 'turn/end') {
            writeDelta('');
            const failure = readTurnEndError(event);
            if (failure !== null) handlers.onError(failure);
            handlers.onTurnEnd();
            return;
        }
        const message = readEventMessage(frame);
        if (message !== null) handlers.onMessage(message);
        return;
    }

    if (frame.type === 'assistant-stream') {
        const inner = frame.frame;
        if (typeof inner !== 'object' || inner === null) return;
        const f = inner as Record<string, unknown>;
        if (f.type === 'start') {
            // 新一次 attempt 开始，之前累积的增量作废
            writeDelta('');
            return;
        }
        if (f.type === 'chunk') {
            const text = readChunkText(f.chunk);
            if (text.length === 0) return;
            // 增量发出去，同时累积——服务端偶尔会重发累计值，
            // 用「已发到哪」比对能避免同一段文字出现两次
            const sent = readDelta();
            if (text.startsWith(sent)) {
                const delta = text.slice(sent.length);
                writeDelta(text);
                if (delta.length > 0) handlers.onDelta(delta);
            } else {
                handlers.onDelta(text);
                writeDelta(sent + text);
            }
            return;
        }
        if (f.type === 'end') {
            // 只清缓冲。**不要**在这里报「本轮结束」：这不是 turn 结束，
            // 只是某一次 attempt 结束（可能后面还有重试）。
            writeDelta('');
        }
    }
}

/** 从 `{type:'event', event:{...}}` 里取出事件对象 */
function readEventObject(raw: unknown): Record<string, unknown> | null {
    if (typeof raw !== 'object' || raw === null) return null;
    const frame = raw as Record<string, unknown>;
    if (frame.type !== 'event') return null;
    const event = frame.event;
    if (typeof event !== 'object' || event === null || Array.isArray(event)) return null;
    return event as Record<string, unknown>;
}

/**
 * 读 snapshot 的运行态。
 *
 * 实测快照里的运行态不在顶层，取不到就按「不在运行」处理：
 * 后续 `turn/end` 会把输入框放开，误判为「运行中」会一直锁着输入。
 */
function readRunning(snapshot: Record<string, unknown>): boolean {
    if (snapshot.running === true) return true;
    const header = snapshot.header;
    if (typeof header === 'object' && header !== null) {
        if ((header as Record<string, unknown>).running === true) return true;
    }
    return false;
}

/**
 * 从 `turn/end` 事件里读出失败原因。
 *
 * 实测形态：`data.reason = {kind:'error', error:{message, code}}`。
 * 成功结束时 `kind` 是 `'user'` / `'complete'` 之类，不算失败。
 */
function readTurnEndError(event: Record<string, unknown>): string | null {
    const data = event.data;
    if (typeof data !== 'object' || data === null) return null;
    const reason = (data as Record<string, unknown>).reason;
    if (typeof reason !== 'object' || reason === null) return null;
    const r = reason as Record<string, unknown>;
    if (r.kind !== 'error') return null;
    const err = r.error;
    if (typeof err === 'object' && err !== null) {
        const message = (err as Record<string, unknown>).message;
        if (typeof message === 'string' && message.length > 0) return message;
    }
    return '本轮执行失败';
}

/**
 * 从一条落库事件里读出消息。
 *
 * 只认三种 surface 消息：`user/message`、`assistant/message`、`system/message`。
 * 其它事件（`turn/start`、`tool/result`、`request/header` 等）不产生聊天气泡——
 * 把它们也塞进会话区块会让用户看到一堆内部过程。
 */
function readEventMessage(raw: unknown): FollowMessage | null {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
    const record = raw as Record<string, unknown>;
    // `snapshot.records` 是 `{type:'event', event:{...}}`，live 帧直接是 event
    const event = record.type === 'event' && typeof record.event === 'object' && record.event !== null
        ? record.event as Record<string, unknown>
        : record;
    const type = event.type;
    if (typeof type !== 'string') return null;

    const role = type === 'user/message'
        ? 'user'
        : type === 'assistant/message'
            ? 'assistant'
            : type === 'system/message'
                ? 'system'
                : null;
    if (role === null) return null;

    const data = typeof event.data === 'object' && event.data !== null
        ? event.data as Record<string, unknown>
        : {};
    const text = readMessageText(data.message);
    if (text.length === 0) return null;
    return { role, text };
}

/**
 * 从 `data.message` 里拼出正文。
 *
 * 消息体是 `{role, content: [...]}`，`content` 是块数组，文本块形如
 * `{type:'text', text}`。这里只取文本块；图片、文件等首期不展示，
 * 但留一行占位让用户知道「这里还有别的东西」。
 */
function readMessageText(raw: unknown): string {
    if (typeof raw !== 'object' || raw === null) return '';
    const message = raw as Record<string, unknown>;
    const content = message.content;
    if (typeof content === 'string') return content;
    if (!Array.isArray(content)) return '';

    const parts: string[] = [];
    let hasNonText = false;
    for (const block of content) {
        if (typeof block !== 'object' || block === null) continue;
        const b = block as Record<string, unknown>;
        if (b.type === 'text' && typeof b.text === 'string') {
            parts.push(b.text);
        } else {
            hasNonText = true;
        }
    }
    const text = parts.join('');
    // 纯附件消息不该显示成空气泡
    if (text.length === 0 && hasNonText) return '[非文本内容，当前版本不展示]';
    return text;
}

/** 从 assistant-stream 的 chunk 里读文本增量 */
function readChunkText(raw: unknown): string {
    if (typeof raw !== 'object' || raw === null) return '';
    const chunk = raw as Record<string, unknown>;
    // 只有 `text-delta` 带正文增量。DSH 的 `StreamChunk` 联合类型里还有
    // `reasoning-delta`（思考过程）、`tool-call-delta`（工具参数）、
    // `usage`、`finish`、`block-end`——都不该进正文气泡。
    // 尤其 `block-end` 带的是**完整块**，当增量拼会重复整段文字。
    if (chunk.type !== 'text-delta') return '';
    return typeof chunk.text === 'string' ? chunk.text : '';
}
