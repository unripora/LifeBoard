// 与 Host 的一次会话：控制帧 → Noise 握手 → 加密 RPC 泵。
//
// 依赖：`api.ts`（登录/注册/刷新/主机列表）、`controlChannel.ts`（控制帧状态机）、
//       `noise.ts`（Noise IK）、`messages.ts`（业务消息）、`conversation.ts`（会话编排）。
//
// ## 为什么要单独一层
//
// 前面几个模块每层只回答一个问题：字节怎么编、帧怎么分、握手怎么算、会话在做什么。
// 界面真正需要的是「连上某台 Host，然后一直把消息推进来」。这个循环必须在一处
// 写完，否则它要么散在 `sessionStart` 里变成一团，要么每加一个功能就复制一遍。
//
// ## 两个并发源
//
// - **拉取循环**：`ctx.ws.recv` 是长挂起的 Promise，一条连接同一时刻只能有一个。
// - **业务投递**：`send` 只是把帧塞进 WS，同步返回，随时可调。
//
// 因此泵只做「读一帧 → 分类 → 转回调」，业务侧直接构造消息并发送，两者不需要互斥。
//
// ## 不收不处理：握手帧要自己接
//
// `ControlChannel` 把 `secure.handshake` 当作「交给你处理」的信号，它不建 Noise。
// 因此本类必须在**泵的同一条读循环里**处理握手，否则握手帧会被丢弃、
// 加密通道永远建不起来。
//
// ## 断线不自动重连
//
// 重连要先刷新 token、再走一遍握手，属于策略而不是传输细节。泵只把原因交回
// `onClosed`，由 `main.ts` 决定是否重试——这样「为什么掉线」永远能说出来。

/// <reference path="../types/index.d.ts" />

import {
    deriveWsUrl,
    listHostDevices,
    loadAuth,
    loadDeviceCredentials,
    loadIdentity,
    needsRefresh,
    refreshDevice,
    saveDeviceCredentials,
    type HostDevice,
} from './api.js';
import { ControlChannel, ControlStateError } from './controlChannel.js';
import { createNoisePrologue, NoiseIkSession, NoiseSessionError } from './noise.js';
import { fromBase64Url, toBase64Url, utf8, fromUtf8 } from './keys.js';
import { ProtocolError, type RelayFrame } from './protocol.js';
import { createRpcRequest, encodeMessage, readInbound, unwrapRemoteResult } from './messages.js';
import type { RemoteMessage } from './messages.js';
import { ConversationController } from './conversation.js';
import { createSessionCreateRequest, readSessionCreateResult } from './sessions.js';

/** 建立一条到 Host 的连接所需的全部输入 */
export interface HostSessionOptions {
    /** 目标 Host 的 deviceId */
    hostDeviceId: string;
    /** 要跟随的会话；为空则新建一个 */
    sessionId?: string;
}

/** 连接过程中的失败分类，决定界面该说什么 */
export type SessionFailureKind =
    | 'auth' // 凭据缺失/过期，需要重新登录
    | 'not-found' // 本机还没注册或找不到目标 Host
    | 'offline' // Host 不在线
    | 'protocol' // 协议不一致（重连无用）
    | 'network' // 网络抖动（可以重试）
    | 'remote'; // Host 业务拒绝

export class HostSessionError extends Error {
    constructor(
        readonly kind: SessionFailureKind,
        readonly code: string,
        message: string,
    ) {
        super(message);
    }
}

/** 界面需要监听的事件 */
export interface HostSessionHandlers {
    /** 连接就绪，可以发消息 */
    onReady(hostName: string, sessionId: string): void;
    /** 助手回复的累计文本（不是增量） */
    onAssistantText(text: string): void;
    /** 助手推理内容（与正文分开，可折叠） */
    onReasoning(text: string): void;
    /** 待审批 */
    onApproval(eventId: string, toolName: string, reason: string): void;
    /** 待回答 */
    onQuestion(eventId: string, question: string): void;
    /** 审批/问答被 Host 撤回 */
    onInteractionCancelled(eventId: string): void;
    /** 一次 RPC 失败 */
    onRequestFailed(code: string, message: string): void;
    /** 连接结束；`failure` 为 null 表示正常断开 */
    onClosed(reason: string, failure: HostSessionError | null): void;
}

/** 建连所需的、在构造前就要拿到的凭据 */
export interface HostSessionTarget {
    host: HostDevice;
    identity: { publicKey: string; privateKey: string };
    serverUrl: string;
    deviceId: string;
    accessToken: string;
}

/**
 * 一次 Host 会话。
 *
 * 用法：`open()` 返回后由后台泵推进；`sendPrompt` / `approve` 直接发。
 */
export class HostSession {

    private readonly control: ControlChannel;
    private noise?: NoiseIkSession;
    private wsId?: string;
    private connectionId = '';
    private ready = false;
    private closed = false;
    private starting = false;

    private sessionIdValue = '';
    private followStreamId = '';
    private eventsStreamId = '';

    private readonly promptIds = new Set<string>();
    private readonly conversation: ConversationController;

    /** 等待 `harness.remote.call` 响应的回调表 */
    private readonly waiting = new Map<string, {
        resolve: (value: unknown) => void;
        reject: (error: Error) => void;
    }>();

    constructor(
        private readonly handlers: HostSessionHandlers,
        private readonly options: HostSessionOptions,
        private readonly target: HostSessionTarget,
    ) {
        this.conversation = new ConversationController({
            onText: (text, reasoning) => {
                this.handlers.onAssistantText(text);
                if (reasoning.length > 0) this.handlers.onReasoning(reasoning);
            },
            onApproval: (request) => {
                this.handlers.onApproval(request.eventId, request.toolName, request.reason ?? '');
            },
            onQuestion: (request) => {
                const first = request.questions[0];
                this.handlers.onQuestion(
                    request.eventId,
                    first === undefined ? '需要你确认' : first.question,
                );
            },
            onInteractionCancelled: (eventId) => this.handlers.onInteractionCancelled(eventId),
            onRequestFailed: (requestId, code, message) => {
                this.promptIds.delete(requestId);
                this.handlers.onRequestFailed(code, message);
            },
        });
        this.control = new ControlChannel({
            onSecureHandshake: (frame) => this.onSecureHandshake(frame),
            onRelay: (frame) => this.onRelay(frame),
            onConnectRejected: (payload) => {
                const code = typeof payload.code === 'string' ? payload.code : 'CONNECT_REJECTED';
                this.fail(new HostSessionError('remote', code, '电脑拒绝了这次连接'));
            },
            onServerError: (error) => {
                ctx.log.w('dsh', `服务端错误：${error.code} ${error.message}`);
            },
        });
    }

    get active(): boolean {
        return this.ready && !this.closed;
    }

    get sessionId(): string {
        return this.sessionIdValue;
    }

    // -----------------------------------------------------------------------
    // 建连前准备
    // -----------------------------------------------------------------------

    /**
     * 取凭据、必要时刷新 token、确认目标 Host 在线。
     *
     * 拆成静态方法：失败时根本没有 `HostSession` 可谈，
     * 塞进实例方法会让构造函数需要容纳一堆尚未就绪的字段。
     *
     * @throws {HostSessionError} 凭据缺失、Host 不在线等
     */
    static async prepare(hostDeviceId: string): Promise<HostSessionTarget> {
        const auth = loadAuth();
        const identity = loadIdentity();
        if (auth === null || identity === null) {
            throw new HostSessionError('auth', 'NOT_LOGGED_IN', '还没有登录 DSH 账号');
        }
        if (hostDeviceId.length === 0) {
            throw new HostSessionError('not-found', 'HOST_REQUIRED', '还没有选择要连接的电脑');
        }

        const stored = loadDeviceCredentials();
        if (stored === null) {
            throw new HostSessionError('auth', 'DEVICE_NOT_REGISTERED', '本机尚未注册为设备，请重新登录');
        }
        // 刷新会轮换 refresh token 且禁止并发，因此只在建连时做一次
        const credentials = needsRefresh(stored.deviceAccessTokenExpiresAt)
            ? await refreshDevice(auth.serverUrl, auth.deviceId, stored.deviceRefreshToken)
            : stored;
        if (credentials !== stored) saveDeviceCredentials(credentials);

        let hosts: HostDevice[];
        try {
            hosts = await listHostDevices(auth.serverUrl, credentials.deviceAccessToken);
        } catch (error) {
            throw toSessionError(error, 'network');
        }
        const host = hosts.find((item) => item.deviceId === hostDeviceId);
        if (host === undefined) {
            throw new HostSessionError('not-found', 'HOST_NOT_FOUND', '这台电脑不在你的账号下');
        }
        if (!host.online) {
            throw new HostSessionError('offline', 'HOST_OFFLINE', `${host.name} 当前离线`);
        }

        return {
            host,
            identity,
            serverUrl: auth.serverUrl,
            deviceId: auth.deviceId,
            accessToken: credentials.deviceAccessToken,
        };
    }

    // -----------------------------------------------------------------------
    // 建连
    // -----------------------------------------------------------------------

    /**
     * WS → hello → connect.request → 启动泵。
     *
     * Noise 由泵在收到 `secure.handshake` 时推进：握手帧和业务消息走同一条
     * 读循环，单独再开一个 `recv` 会直接触发宿主「同一连接只允许一个 recv」。
     */
    async open(): Promise<boolean> {
        try {
            this.wsId = await ctx.ws.connect(deriveWsUrl(this.target.serverUrl));
        } catch (error) {
            this.fail(new HostSessionError('network', 'WS_CONNECT_FAILED', '无法连接服务器，请检查网络'));
            return false;
        }

        try {
            // hello 必须在建连后立刻发（§7 要求 5 秒内）
            this.ctxSend(this.control.createHello({
                deviceId: this.target.deviceId,
                accessToken: this.target.accessToken,
                role: 'client',
            }));

            const acked = await this.readUntilHelloAck();
            if (!acked) {
                this.fail(new HostSessionError('network', 'WS_CLOSED', '连接在握手完成前就断开了'));
                return false;
            }

            // connect.request 先发，Host 才会推 secure.handshake
            this.ctxSend(this.control.send('connect.request', {
                hostDeviceId: this.target.host.deviceId,
                protocols: [1],
                transports: ['relay'],
            }));

            // 泵不 await：`sessionStart` 需要尽快返回，真正就绪由 onReady 通知
            void this.pump();
            return true;
        } catch (error) {
            this.fail(toSessionError(error, 'protocol'));
            return false;
        }
    }

    /** 读帧直到 `hello.ack`。之前收到的帧不处理：业务还没开始 */
    private async readUntilHelloAck(): Promise<boolean> {
        const wsId = this.requireWs();
        for (;;) {
            const frame = await ctx.ws.recv(wsId);
            if (frame.type === 'close') return false;
            if (frame.type !== 'text') continue;
            const step = this.control.handle(frame.data);
            for (const send of step.sends) this.ctxSend(send);
            if (step.ack !== undefined) {
                ctx.log.i('dsh', `已连接服务器 ${step.ack.serverVersion}`);
                return true;
            }
        }
    }

    // -----------------------------------------------------------------------
    // 泵
    // -----------------------------------------------------------------------

    private async pump(): Promise<void> {
        const wsId = this.requireWs();
        try {
            for (;;) {
                const frame = await ctx.ws.recv(wsId);
                if (frame.type === 'close') {
                    this.finish('与电脑的连接已断开');
                    return;
                }
                if (frame.type !== 'text') continue;
                const step = this.control.handle(frame.data);
                for (const send of step.sends) this.ctxSend(send);
                if (step.error !== undefined && !this.ready) {
                    // 握手阶段的错误帧：直接结束，别让用户对着「正在连接」干等
                    this.fail(new HostSessionError('remote', step.error.code, step.error.message));
                    return;
                }
            }
        } catch (error) {
            if (this.closed) return;
            this.fail(toSessionError(error, 'network'));
        }
    }

    // -----------------------------------------------------------------------
    // Noise
    // -----------------------------------------------------------------------

    /**
     * 收到 `secure.handshake`：按 `canWriteHandshake` 决定读还是写。
     *
     * 用「谁该写就写谁」而不是按 `step` 分支：step 是对方的编号，
     * 而 Noise 的顺序约束才是权威，混用两套判断迟早会错一次。
     */
    private onSecureHandshake(frame: { connectionId: string; step: 1 | 2; data: string }): void {
        if (this.closed) return;
        this.connectionId = frame.connectionId;

        if (this.noise === undefined) {
            this.noise = new NoiseIkSession({
                role: 'initiator',
                localPrivateKey: this.target.identity.privateKey,
                localPublicKey: this.target.identity.publicKey,
                remotePublicKey: this.target.host.identityKey,
                prologue: createNoisePrologue(
                    frame.connectionId,
                    this.target.host.deviceId,
                    this.target.deviceId,
                ),
            });
        }

        try {
            if (frame.step === 1) {
                // Host 的第一条消息；接收后会轮到我们写 message2
                this.noise.readHandshake(fromBase64Url(frame.data));
            }
            if (this.noise.canWriteHandshake) {
                const reply = this.noise.writeHandshake();
                this.ctxSend(this.control.send('secure.handshake', {
                    connectionId: this.connectionId,
                    targetDeviceId: this.target.host.deviceId,
                    step: 2,
                    data: toBase64Url(reply),
                }));
            }
        } catch (error) {
            this.fail(toSessionError(error, 'protocol'));
            return;
        }

        if (this.noise.complete) {
            ctx.log.i('dsh', '安全通道已建立');
            void this.startConversation();
        }
    }

    // -----------------------------------------------------------------------
    // 会话
    // -----------------------------------------------------------------------

    /** 握手完成后进入真正的会话：选/建会话、跟随、打开事件流 */
    private async startConversation(): Promise<void> {
        if (this.starting || this.ready) return;
        this.starting = true;
        try {
            const wanted = this.options.sessionId ?? '';
            if (wanted.length === 0) {
                const result = await this.rpc(createSessionCreateRequest());
                this.sessionIdValue = readSessionCreateResult(unwrapRemoteResult(result));
            } else {
                this.sessionIdValue = wanted;
            }

            this.followStreamId = `follow-${this.sessionIdValue}`;
            this.sendBusiness(this.conversation.attachSession(this.sessionIdValue, this.followStreamId));

            this.eventsStreamId = 'events-1';
            this.sendBusiness(this.conversation.openEvents(this.eventsStreamId));

            this.ready = true;
            this.handlers.onReady(this.target.host.name, this.sessionIdValue);
        } catch (error) {
            this.fail(toSessionError(error, 'remote'));
        }
    }

    /**
     * 发一条 `harness.remote.call` 并等响应。
     *
     * 自己维护等待表而不是走编排器：后者只跟踪会话相关请求，且结算依赖泵
     * 把响应喂进去——在 `ready` 之前调用会永远等不到。
     *
     * 超时不在这里做：QuickJS 没有 `setTimeout`，宿主的「Promise 长时间不落地」
     * 机制会放弃这次调用（见 `JsSourcePlugin.invoke`）。
     */
    private rpc(message: RemoteMessage): Promise<unknown> {
        const requestId = message.id;
        this.sendBusiness(message);
        return new Promise<unknown>((resolve, reject) => {
            this.waiting.set(requestId, { resolve, reject });
        });
    }

    private handleBusiness(text: string): void {
        let inbound;
        try {
            inbound = readInbound(text);
        } catch (error) {
            ctx.log.w('dsh', `业务消息无法解析：${error instanceof Error ? error.message : String(error)}`);
            return;
        }

        if (inbound.kind === 'response' || inbound.kind === 'error') {
            const waiter = this.waiting.get(inbound.requestId);
            if (waiter !== undefined) {
                this.waiting.delete(inbound.requestId);
                if (inbound.kind === 'response') waiter.resolve(inbound.result);
                else waiter.reject(new HostSessionError('remote', inbound.code, inbound.message));
            }
        }

        // 会话相关的推进（流帧、事件帧、prompt 结果）统一交给编排器
        this.conversation.handleMessage(text);
    }

    // -----------------------------------------------------------------------
    // 业务入口（供 main.ts 的四条会话函数使用）
    // -----------------------------------------------------------------------

    /** 发一条用户消息；返回 false 表示连接不可用 */
    sendPrompt(text: string): boolean {
        if (!this.active) return false;
        const message = this.conversation.sendPrompt(text);
        this.promptIds.add(message.id);
        this.sendBusiness(message);
        return true;
    }

    approve(eventId: string, allowed: boolean): boolean {
        if (!this.active) return false;
        this.sendBusiness(this.conversation.approve(eventId, allowed ? 'allowed-once' : 'rejected'));
        return true;
    }

    answer(eventId: string, questionId: string, selected: string[]): boolean {
        if (!this.active) return false;
        this.sendBusiness(
            this.conversation.answerQuestion(eventId, [{ id: questionId, selected }]),
        );
        return true;
    }

    /** 关闭跟随流。协议没有「取消一轮生成」的 RPC，只能停本地跟随 */
    stop(): void {
        if (!this.active || this.followStreamId.length === 0) return;
        this.sendBusiness(
            createRpcRequest('harness.remote.stream.close', { streamId: this.followStreamId }),
        );
        this.promptIds.clear();
    }

    /** 关闭连接。可重复调用 */
    close(): void {
        if (this.closed) return;
        this.closed = true;
        this.ready = false;
        this.noise?.destroy();
        this.noise = undefined;
        for (const [, waiter] of this.waiting) {
            waiter.reject(new HostSessionError('network', 'CLOSED', '连接已关闭'));
        }
        this.waiting.clear();
        const wsId = this.wsId;
        this.wsId = undefined;
        if (wsId !== undefined) {
            try {
                ctx.ws.close(wsId, 1000, 'client done');
            } catch {
                // 已经断了，忽略
            }
        }
    }

    // -----------------------------------------------------------------------
    // 发送
    // -----------------------------------------------------------------------

    private onRelay(frame: RelayFrame): void {
        const session = this.noise;
        if (session === undefined || !session.complete) {
            ctx.log.w('dsh', '收到 relay 但安全通道尚未建立，已丢弃');
            return;
        }
        let plaintext: string;
        try {
            plaintext = fromUtf8(session.decrypt(fromBase64Url(frame.ciphertext)));
        } catch (error) {
            // 解不开说明两侧密钥不一致，继续读只会全部失败
            this.fail(new HostSessionError('protocol', 'DECRYPT_FAILED', '消息解密失败，连接不可用'));
            return;
        }
        this.handleBusiness(plaintext);
    }

    private ctxSend(text: string): void {
        const wsId = this.wsId;
        if (wsId === undefined) throw new HostSessionError('network', 'WS_CLOSED', '连接已关闭');
        ctx.ws.send(wsId, text);
    }

    /** 业务消息必须装进 relay：先加密，再作为控制帧发出去 */
    private sendBusiness(message: RemoteMessage): void {
        const session = this.noise;
        if (session === undefined || !session.complete) {
            throw new HostSessionError('network', 'NOT_READY', '安全通道尚未就绪');
        }
        const ciphertext = session.encrypt(utf8(encodeMessage(message)));
        this.ctxSend(this.control.send('relay', {
            connectionId: this.connectionId,
            targetDeviceId: this.target.host.deviceId,
            counter: Number(session.sendingCounter()),
            ciphertext: toBase64Url(ciphertext),
        }));
    }

    private requireWs(): string {
        if (this.wsId === undefined) throw new HostSessionError('network', 'WS_CLOSED', '连接已关闭');
        return this.wsId;
    }

    /** 正常收尾 */
    private finish(reason: string): void {
        if (this.closed) return;
        this.close();
        this.handlers.onClosed(reason, null);
    }

    /** 出错收尾：关连接、报原因，避免留下一个半死不活的会话 */
    private fail(error: HostSessionError): void {
        if (this.closed) return;
        this.close();
        this.handlers.onClosed(error.message, error);
    }
}

/** 把底层异常翻成界面能照做的一类错误 */
export function toSessionError(error: unknown, fallback: SessionFailureKind): HostSessionError {
    if (error instanceof HostSessionError) return error;
    if (error instanceof NoiseSessionError) {
        return new HostSessionError('protocol', error.code, `安全握手失败：${error.message}`);
    }
    if (error instanceof ControlStateError) {
        return new HostSessionError('protocol', error.code, error.message);
    }
    if (error instanceof ProtocolError) {
        return new HostSessionError('protocol', error.code, error.message);
    }
    const message = error instanceof Error ? error.message : String(error);
    return new HostSessionError(fallback, 'UNKNOWN', message);
}
