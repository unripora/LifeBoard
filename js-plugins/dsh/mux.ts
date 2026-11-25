// `/api/remote.mux` 逻辑流：DSH 网关把每条 WS 文本消息当成一个逻辑流帧。
//
// 依据：`deepseek-ai/deepseek-harness` 的
//   `packages/api/gateway/src/stream-protocol.ts`（帧定义与逐字段校验）
//   `packages/api/session-controller/src/types.ts`（业务帧 `SessionFollowFrame`）
//
// ## 为什么用「拉取」而不是回调
//
// 宿主函数只能收发 JSON，JS 函数传不进 `ctx.ws`，插件注册不了 onMessage。
// 所以沿用 `ctx.ws.recv` 的拉取模型：一条 mux 连接上多路复用多条逻辑流，
// 由本模块把帧按 `streamId` 分发到各自的等待者。
//
// ## 校验必须严格
//
// 网关对**每个**字段做 `exactKeys` 校验（多一个键都拒）。反过来，我们收帧时
// 也不能「宽松地猜」：畸形帧若被当成正常帧继续处理，表现是消息内容错乱，
// 很难回溯到「少了一个必填字段」。所以这里对收到的帧同样做键位白名单检查。
//
// ## 一条连接、多条流
//
// `streamId` 由客户端生成。网关按它路由回帧，所以必须全局唯一（这里用
// 单调序号 + 随机前缀，避免插件重载后与旧会话撞号）。

/// <reference path="../types/index.d.ts" />

import { deriveMuxUrl, gatewayCookieHeader, hostOf } from './gateway.js';

/** 客户端 → 服务的四种帧 */
export type ClientFrame =
    | { type: 'open'; streamId: string; endpoint: string; payload: unknown }
    | { type: 'item'; streamId: string; value?: unknown }
    | { type: 'end'; streamId: string }
    | { type: 'cancel'; streamId: string };

/** 服务端 → 客户端的三种帧 */
export type ServerFrame =
    | { type: 'item'; streamId: string; value?: unknown }
    | { type: 'end'; streamId: string }
    | { type: 'error'; streamId: string; error: { code: string; message: string; details: Record<string, unknown> } };

/** 一条逻辑流的失败，带上网关原始的 code 便于分类处理 */
export class MuxError extends Error {
    constructor(
        readonly code: string,
        message: string,
        readonly details: Record<string, unknown> = {},
    ) {
        super(message);
    }
}

/** 一条逻辑流的句柄：拿到它才能发上行帧、取消、以及读下行 */
export interface MuxStream {
    readonly streamId: string;
    /** 取下一帧下行值；流终结时抛 [MuxError] */
    next(): Promise<unknown>;
    /** 上行一帧（少数端点在 open 之后还要继续送数据） */
    send(value: unknown): void;
    /** 上行半关闭：告诉服务端「我不再发了，但仍要继续收」 */
    end(): void;
    /** 取消整条流：收发都不再继续 */
    cancel(): void;
}

/**
 * 一条 mux 连接。
 *
 * 生命周期：构造 → [open] 建多条流 → [close]。
 * 连接断开时所有未终结的流都会以 [MuxError] 结束，不会永久挂起。
 */
export class MuxConnection {
    /** streamId → 该流的帧队列与等待者 */
    private readonly streams = new Map<string, StreamState>();
    /** 网关连接 id（`ctx.ws.connect` 的返回值） */
    private readonly connId: string;
    private closed = false;
    private pumpError: string | null = null;

    private constructor(connId: string) {
        this.connId = connId;
    }

    /**
     * 建立 mux 连接。
     *
     * 必须带 `Origin`（网关对 WS 的 Origin 是强制项，缺失直接 403），
     * 并带上会话 Cookie——由 `openGatewaySession` 种在会话里的那份。
     *
     * ## 为什么 Cookie 要自己拼
     *
     * WS 与 HTTP 是两套 `OkHttpClient`：`CookieJar` 挂在 HTTP 会话上，
     * WebSocket 握手不会自动带上它。网关的 `authorize()` 只读 Cookie，
     * 不带就是 401（`Expected HTTP 101 response but was '401 Unauthorized'`）。
     *
     * 而且手动种的 cookie 没有 `Secure` 属性，宿主按 URL 匹配时会被
     * OkHttp 排除掉——直接取会话全部 cookie 值拼头更可靠。
     */
    static async connect(gateway: string): Promise<MuxConnection> {
        const host = hostOf(gateway);
        const cookie = gatewayCookieHeader();
        const headers: Record<string, string> = {
            origin: gateway,
            host,
        };
        if (cookie.length > 0) headers['Cookie'] = cookie;
        const connId = await ctx.ws.connect(deriveMuxUrl(gateway), { headers });
        const mux = new MuxConnection(connId);
        // 泵不 await：它要活到连接结束，而调用方现在就要拿到句柄
        mux.startPump();
        return mux;
    }

    /**
     * 开一条逻辑流。
     *
     * `payload` 的字段由各 endpoint 自己规定，但 `open` 这一帧的四个字段
     * （`type`/`streamId`/`endpoint`/`payload`）一个都不能少——网关用
     * `exactKeys` 校验，少一个就是整条连接被拒。
     */
    open(endpoint: string, payload: unknown, streamId: string = nextStreamId()): MuxStream {
        if (this.closed) throw new MuxError('closed', 'mux 连接已关闭');
        const state: StreamState = { queue: [], waiter: null, ended: false };
        this.streams.set(streamId, state);
        this.sendFrame({ type: 'open', streamId, endpoint, payload });
        return {
            streamId,
            next: () => this.nextItem(streamId, state),
            send: (value) => this.sendFrame({ type: 'item', streamId, value }),
            end: () => this.sendFrame({ type: 'end', streamId }),
            cancel: () => {
                this.sendFrame({ type: 'cancel', streamId });
                this.finishStream(streamId, state, new MuxError('cancelled', '流已取消'));
            },
        };
    }

    /** 关连接。所有未终结的流立刻以 [MuxError] 结束，不会有 Promise 悬着。 */
    close(): void {
        if (this.closed) return;
        this.closed = true;
        for (const [id, state] of this.streams) {
            this.finishStream(id, state, new MuxError('closed', 'mux 连接已关闭'));
        }
        ctx.ws.close(this.connId, 1000, '');
    }

    // ---------- 内部 ----------

    private sendFrame(frame: ClientFrame): void {
        if (this.closed) throw new MuxError('closed', 'mux 连接已关闭');
        // 网关要求 `value` 键**存在**（可以是 null），所以显式补齐：
        // JSON.stringify 会丢掉 undefined 字段，导致 exactKeys 校验失败
        const wire = frame.type === 'item'
            ? { ...frame, value: frame.value === undefined ? null : frame.value }
            : frame;
        ctx.ws.send(this.connId, JSON.stringify(wire));
    }

    private startPump(): void {
        void (async () => {
            try {
                for (;;) {
                    const frame = await ctx.ws.recv(this.connId);
                    if (frame.type === 'close') {
                        this.pumpError = frame.error ?? frame.reason ?? '连接已关闭';
                        break;
                    }
                    if (frame.type !== 'text') continue;
                    const parsed = parseServerFrame(frame.data);
                    if (parsed !== null) this.deliver(parsed);
                }
            } catch (error) {
                this.pumpError = error instanceof Error ? error.message : String(error);
            } finally {
                this.closed = true;
                const reason = this.pumpError ?? 'mux 连接已断开';
                for (const [id, state] of this.streams) {
                    this.finishStream(id, state, new MuxError('closed', reason));
                }
            }
        })();
    }

    private deliver(frame: ServerFrame): void {
        const state = this.streams.get(frame.streamId);
        // 未知 streamId 直接丢：可能是已取消的流的迟到帧，
        // 若因此报错会让一条无辜的流被牵连
        if (state === undefined || state.ended) return;

        if (frame.type === 'item') {
            this.push(state, { kind: 'item', value: frame.value });
            return;
        }
        if (frame.type === 'end') {
            this.push(state, { kind: 'error', error: new MuxError('ended', '流已结束') });
            this.finishStream(frame.streamId, state, new MuxError('ended', '流已结束'));
            return;
        }
        const error = new MuxError(frame.error.code, frame.error.message, frame.error.details);
        this.push(state, { kind: 'error', error });
        this.finishStream(frame.streamId, state, error);
    }

    private push(state: StreamState, entry: QueueEntry): void {
        if (state.waiter !== null) {
            const resolve = state.waiter;
            state.waiter = null;
            resolve(entry);
            return;
        }
        state.queue.push(entry);
    }

    private nextItem(streamId: string, state: StreamState): Promise<unknown> {
        if (this.closed) return Promise.reject(new MuxError('closed', 'mux 连接已关闭'));
        const pending = state.queue.shift();
        if (pending !== undefined) {
            return pending.kind === 'item'
                ? Promise.resolve(pending.value)
                : Promise.reject(pending.error);
        }
        if (state.ended) return Promise.reject(new MuxError('ended', '流已结束'));
        return new Promise<unknown>((resolve, reject) => {
            state.waiter = (entry) => {
                if (entry.kind === 'item') resolve(entry.value);
                else reject(entry.error);
            };
            // streamId 只为可读性保留，实际状态挂在 state 上
            void streamId;
        });
    }

    private finishStream(streamId: string, state: StreamState, error: MuxError): void {
        if (state.ended) return;
        state.ended = true;
        if (state.waiter !== null) {
            const resolve = state.waiter;
            state.waiter = null;
            resolve({ kind: 'error', error });
        }
        this.streams.delete(streamId);
    }
}

interface StreamState {
    queue: QueueEntry[];
    waiter: ((entry: QueueEntry) => void) | null;
    ended: boolean;
}

type QueueEntry =
    | { kind: 'item'; value: unknown }
    | { kind: 'error'; error: MuxError };

/**
 * 解析一条下行帧。
 *
 * 畸形帧返回 `null` 而不是抛错：一条坏帧不该把整条连接判死，
 * 但也不能当正常帧用——所以丢弃。
 *
 * **不依赖 `ctx`**：这是纯解析函数，调用方（包括离线自检）不该为了解析
 * 一段 JSON 去准备一份宿主上下文。
 */
export function parseServerFrame(text: string): ServerFrame | null {
    let decoded: unknown;
    try {
        decoded = JSON.parse(text) as unknown;
    } catch {
        return null;
    }
    if (typeof decoded !== 'object' || decoded === null || Array.isArray(decoded)) {
        return null;
    }
    const frame = decoded as Record<string, unknown>;
    const streamId = frame.streamId;
    if (typeof streamId !== 'string' || streamId.length === 0) return null;

    if (frame.type === 'item') {
        const keys = Object.keys(frame);
        if (keys.some((k) => k !== 'type' && k !== 'streamId' && k !== 'value')) return null;
        return { type: 'item', streamId, value: frame.value };
    }
    if (frame.type === 'end') {
        if (Object.keys(frame).length !== 2) return null;
        return { type: 'end', streamId };
    }
    if (frame.type === 'error') {
        const error = frame.error;
        if (typeof error !== 'object' || error === null || Array.isArray(error)) return null;
        const e = error as Record<string, unknown>;
        if (typeof e.code !== 'string' || typeof e.message !== 'string') return null;
        const details = typeof e.details === 'object' && e.details !== null && !Array.isArray(e.details)
            ? e.details as Record<string, unknown>
            : {};
        return { type: 'error', streamId, error: { code: e.code, message: e.message, details } };
    }
    return null;
}

/** streamId 序号：全局唯一，避免插件重载后与旧流撞号 */
let streamSeq = 0;

function nextStreamId(): string {
    streamSeq += 1;
    return `lb-${Math.floor(Math.random() * 1e9).toString(36)}-${streamSeq}`;
}
