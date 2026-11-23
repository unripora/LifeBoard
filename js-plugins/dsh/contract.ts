// DSH Remote 插件的契约出口：登录、详情区块、会话四函数。
//
// 依赖：`api.ts`（账号/设备/主机）、`keys.ts`（身份密钥）、`hostSession.ts`（连接与本体会话）。
//
// ## 为什么状态全放模块变量
//
// 宿主每次调用都注入一个新的 `ctx`，模块变量才是唯一能跨调用存活的地方
// （见编写指南「中间态只能放模块变量」）。`activeSession` 尤其如此：
// `sessionStart` 建的连接必须在 `sessionEvent` / `sessionStop` 里还能拿到。
//
// ## 会话四函数与宿主的约定
//
// | 导出 | 何时调用 | 必须做什么 |
// |---|---|---|
// | `sessionStart` | 详情页出现 `conversation` 区块 | 建连；**显式** `return true` 才算成功 |
// | `sessionEvent` | 用户提交输入 | 把文本送给 Host；返回 `{action:'stop'}` 表示会话结束 |
// | `sessionStop` | 离开详情页/撤销信任 | 断连；可能被重复调用 |
// | `ctx.session.emit` | 会话期间任意时刻 | 把消息/状态推给界面 |
//
// ## 登录态
//
// 账号 token 用完即弃；常驻的是设备 token pair 与身份私钥。
// `authState` 只看本地槽位，**不发网络请求**：详情页每次进入都会问一次，
// 让它联网等于每次进页都打一次服务器。

/// <reference path="../types/index.d.ts" />

import {
    clearAllAuth,
    loginWithPassword,
    loadAuth,
    loadDeviceCredentials,
    listHostDevices,
    normalizeServerUrl,
    registerDevice,
    saveAuth,
    saveDeviceCredentials,
    saveIdentity,
    type DeviceCredentials,
    type HostDevice,
} from './api.js';
import { generateKeyPair, identityFingerprint } from './keys.js';
import { HostSession, HostSessionError, toSessionError, type SessionFailureKind } from './hostSession.js';

/** 凭据槽位，与 manifest 的 credentialSpec 对齐 */
const SLOT_EMAIL = 'email';
const SLOT_PASSWORD = 'password';
const SLOT_SERVER = 'serverUrl';

/** 选中的 Host 记在普通凭据槽位里：它不是秘密，但也没别的地方好放 */
const SLOT_HOST = 'selectedHost';

/** 详情页会话区块固定的 sessionId（首期单会话） */
const SESSION_ID = 'main';

/** 当前连接。模块级：跨调用存活 */
let activeSession: HostSession | null = null;

/** 最近一次连接失败的原因，供 `sessionStart` 返回 false 后 `detailUi` 展示 */
let lastFailure: { kind: SessionFailureKind; message: string } | null = null;

/** 登录中间态：多轮登录用 */
let pendingLogin: { serverUrl: string; email: string; password: string } | null = null;

// ---------------------------------------------------------------------------
// 登录
// ---------------------------------------------------------------------------

interface LoginResultNeedInput {
    type: 'needInput';
    prompt: string;
    fields: Array<{ name: string; displayName?: string; secret?: boolean; hint?: string }>;
}

type LoginResult =
    | { type: 'success' }
    | LoginResultNeedInput
    | { type: 'failure'; message: string };

/**
 * 两轮登录：先账号密码 → 换 accountToken → 注册本机为 client 设备。
 *
 * 只有两轮：DSH 没有验证码，第二轮是「确认后写凭据」，
 * 把它拆开是为了让用户能看清「登录的是哪个账号、注册的是哪台设备」。
 */
export async function login(inputs?: Record<string, string>): Promise<LoginResult> {
    const form = inputs ?? {};
    try {
        if (pendingLogin === null) {
            const email = (form[SLOT_EMAIL] ?? '').trim();
            const password = form[SLOT_PASSWORD] ?? '';
            const rawServer = (form[SLOT_SERVER] ?? '').trim();
            if (email.length === 0 || password.length === 0) {
                return {
                    type: 'needInput',
                    prompt: '登录 DSH 账号',
                    fields: [
                        { name: SLOT_EMAIL, displayName: '邮箱', secret: false },
                        { name: SLOT_PASSWORD, displayName: '密码', secret: true },
                        {
                            name: SLOT_SERVER,
                            displayName: '服务器',
                            secret: false,
                            hint: rawServer.length > 0 ? rawServer : '留空使用默认服务器',
                        },
                    ],
                };
            }
            pendingLogin = { serverUrl: rawServer, email, password };
            return await completeLogin(pendingLogin);
        }

        // 第二轮：用户点了「继续」，沿用上次输入
        const pending = pendingLogin;
        pendingLogin = null;
        return await completeLogin(pending);
    } catch (error) {
        pendingLogin = null;
        const message = error instanceof Error ? error.message : String(error);
        ctx.log.e('dsh', `登录失败：${message}`);
        return { type: 'failure', message: `登录失败：${message}` };
    }
}

async function completeLogin(input: { serverUrl: string; email: string; password: string }): Promise<LoginResult> {
    const serverUrl = normalizeServerUrl(input.serverUrl);
    const account = await loginWithPassword(serverUrl, input.email, input.password);

    // 每次登录都换一套身份密钥：旧私钥可能已经泄露（换机、重装前的备份），
    // 而设备身份与密钥绑定，沿用旧的等于把旧身份继续用下去
    const pair = generateKeyPair();
    const credentials: DeviceCredentials = await registerDevice(serverUrl, account.token, {
        deviceId: newDeviceId(),
        name: 'LifeBoard',
        platform: 'android',
        identityKey: pair.publicKey,
        clientVersion: '1.0',
    });

    saveIdentity(pair);
    saveDeviceCredentials(credentials);
    saveAuth({
        serverUrl,
        account: account.account,
        deviceId: currentDeviceId(),
        deviceName: 'LifeBoard',
        identityPublicKey: pair.publicKey,
    });
    ctx.log.i('dsh', `登录成功，设备指纹 ${identityFingerprint(pair.publicKey)}`);
    return { type: 'success' };
}

/**
 * 设备 id。
 *
 * 首次登录时生成一次并跟着账号一起存：服务端按它做设备归属校验，
 * 每次登录都换 id 会在服务端堆出一串孤儿设备。
 */
let deviceIdValue = '';

function currentDeviceId(): string {
    return deviceIdValue;
}

function newDeviceId(): string {
    if (deviceIdValue.length === 0) {
        deviceIdValue = `lb-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
    }
    return deviceIdValue;
}

export function logout(): void {
    clearAllAuth();
    deviceIdValue = '';
    closeSession();
}

export function authState(): { state: 'loggedOut' } | { state: 'loggedIn'; accountName?: string } {
    const auth = loadAuth();
    const credentials = loadDeviceCredentials();
    if (auth === null || credentials === null) return { state: 'loggedOut' };
    return { state: 'loggedIn', accountName: auth.account };
}

// ---------------------------------------------------------------------------
// 详情页区块
// ---------------------------------------------------------------------------

type UiBlock =
    | { type: 'statusCard'; label: string; value: string; tone?: string; detail?: string | null }
    | { type: 'text'; title: string; body?: string }
    | {
          type: 'conversation';
          id: string;
          sessionId: string;
          title?: string;
          placeholder?: string;
          submitLabel?: string;
          emptyHint?: string;
      };

/**
 * 详情页区块：状态卡 + 会话。
 *
 * 顺序是刻意的：状态卡在会话上方，用户先说清楚「连的是哪台电脑」，
 * 再决定要不要说话。区块带 `id`，用户拖动后的顺序能稳定复现。
 */
export function detailUi(): UiBlock[] {
    const blocks: UiBlock[] = [];

    const auth = loadAuth();
    const host = loadSelectedHost();
    if (auth === null) {
        blocks.push({
            type: 'text',
            title: '还没有登录',
            body: '请先在插件列表里完成登录，再回到这里连接电脑。',
        });
        return blocks;
    }

    if (host === null) {
        blocks.push({
            type: 'statusCard',
            label: '电脑',
            value: '未选择',
            tone: 'warn',
            detail: '登录后会自动发现同账号下在线的电脑',
        });
    } else {
        blocks.push({
            type: 'statusCard',
            label: host.name,
            value: activeSession !== null && activeSession.active ? '已连接' : '未连接',
            tone: activeSession !== null && activeSession.active ? 'ok' : 'neutral',
            detail: host.platform,
        });
    }

    if (lastFailure !== null) {
        blocks.push({
            type: 'text',
            title: '上次连接失败',
            body: lastFailure.message,
        });
    }

    blocks.push({
        type: 'conversation',
        id: 'main',
        sessionId: SESSION_ID,
        title: host === null ? '远程会话' : `${host.name} 的会话`,
        placeholder: '给电脑发指令…',
        submitLabel: '发送',
        emptyHint: '连接后在这里对话',
    });
    return blocks;
}

export async function onAction(actionId: string, value?: string): Promise<unknown> {
    if (actionId === 'dsh.refreshHosts') {
        const hosts = await discoverHosts();
        if (hosts.length === 0) return { type: 'showMessage', text: '没有发现在线的电脑' };
        saveSelectedHost(hosts[0]);
        return { type: 'refreshUi', blocks: detailUi() };
    }
    return { type: 'showMessage', text: value === undefined ? '未知操作' : `已忽略：${actionId}` };
}

export function health(): { state: 'healthy' } | { state: 'degraded'; message: string } {
    if (lastFailure !== null) return { state: 'degraded', message: lastFailure.message };
    return { state: 'healthy' };
}

/**
 * 采集入口。
 *
 * DSH 不是数据源，没有指标要上报：返回空数组会被当成「今天确实没数据」，
 * 而那是**正确**语义——这个插件本来就不产生指标。
 */
export function fetch(): unknown[] {
    return [];
}

// ---------------------------------------------------------------------------
// 会话四函数
// ---------------------------------------------------------------------------

/**
 * 建立到电脑的连接。
 *
 * 返回 true 后宿主才启用输入框；返回 false 时 `detailUi` 里会有失败原因，
 * 用户不必猜「为什么发不出去」。
 */
export async function sessionStart(): Promise<boolean> {
    try {
        closeSession();
        lastFailure = null;

        const host = loadSelectedHost() ?? (await discoverHosts())[0];
        if (host === undefined) {
            const error = new HostSessionError('not-found', 'NO_HOST', '没有发现在线的电脑');
            lastFailure = { kind: error.kind, message: error.message };
            ctx.session.emit(JSON.stringify({ type: 'status', text: error.message, inputEnabled: false }));
            return false;
        }
        saveSelectedHost(host);

        ctx.session.emit(JSON.stringify({ type: 'status', text: '正在连接…', inputEnabled: false }));

        // prepare 会刷新 token、确认在线，失败原因比「连不上」具体得多
        const target = await HostSession.prepare(host.deviceId);
        const session = new HostSession({
            onReady: (hostName, sessionId) => {
                ctx.log.i('dsh', `会话就绪：${hostName} / ${sessionId}`);
                ctx.session.emit(JSON.stringify({
                    type: 'status',
                    text: `已连接 ${hostName}`,
                    inputEnabled: true,
                }));
            },
            onAssistantText: (text) => {
                // `delta` 是累加语义：宿主的控制器会把它拼到当前回复末尾，
                // 因此这里必须发**增量**，不能发累计值
                const emitted = lastAssistantText;
                lastAssistantText = text;
                const delta = text.startsWith(emitted) ? text.slice(emitted.length) : text;
                if (delta.length > 0) {
                    ctx.session.emit(JSON.stringify({ type: 'delta', text: delta }));
                }
            },
            onReasoning: () => {
                // 推理内容首期不展示：会话区块只有正文一条通道，
                // 混进去会让用户以为 Agent 在自言自语
            },
            onApproval: (eventId, toolName, reason) => {
                pendingApprovals.set(eventId, 'approval');
                ctx.session.emit(JSON.stringify({
                    type: 'message',
                    role: 'system',
                    text: `需要审批：${toolName}${reason.length > 0 ? `（${reason}）` : ''}。回复 y 允许一次，n 拒绝。`,
                }));
            },
            onQuestion: (eventId, question) => {
                pendingApprovals.set(eventId, 'question');
                ctx.session.emit(JSON.stringify({
                    type: 'message',
                    role: 'system',
                    text: `需要你回答：${question}（直接回复选项）`,
                }));
            },
            onInteractionCancelled: (eventId) => {
                pendingApprovals.delete(eventId);
            },
            onRequestFailed: (code, message) => {
                ctx.session.emit(JSON.stringify({
                    type: 'message',
                    role: 'system',
                    text: `请求失败：${message}（${code}）`,
                }));
            },
            onClosed: (reason, failure) => {
                closeSession();
                lastFailure = failure === null ? null : { kind: failure.kind, message: failure.message };
                ctx.session.emit(JSON.stringify({ type: 'closed', reason }));
            },
        }, { hostDeviceId: host.deviceId }, target);

        activeSession = session;
        lastAssistantText = '';
        pendingApprovals.clear();

        const opened = await session.open();
        if (!opened) {
            closeSession();
            return false;
        }
        return true;
    } catch (error) {
        const failure = toSessionError(error, 'network');
        lastFailure = { kind: failure.kind, message: failure.message };
        ctx.log.e('dsh', `连接失败：${failure.code} ${failure.message}`);
        ctx.session.emit(JSON.stringify({ type: 'status', text: failure.message, inputEnabled: false }));
        closeSession();
        return false;
    }
}

/** 助手累计文本，用来把累计值换算成增量 */
let lastAssistantText = '';

/** 待处理的审批/问答：eventId → 类型 */
const pendingApprovals = new Map<string, 'approval' | 'question'>();

export interface SessionEventInput {
    type: string;
    payload?: string;
}

export interface SessionActionResult {
    action?: 'continue' | 'stop';
    reason?: string;
}

/**
 * 用户提交了一条输入。
 *
 * 先看是不是在回审批/问答：那条交互挂着等回复时，用户打一个 y 比再点一次按钮
 * 快得多。否则当作普通指令发给 Host。
 */
export async function sessionEvent(event: SessionEventInput): Promise<SessionActionResult> {
    const session = activeSession;
    if (session === null || !session.active) {
        return { action: 'stop', reason: '连接已断开' };
    }

    const text = (event.payload ?? '').trim();
    if (text.length === 0) return { action: 'continue' };

    const pending = firstPending();
    if (pending !== undefined) {
        pendingApprovals.delete(pending.eventId);
        if (pending.kind === 'approval') {
            const allowed = /^(y|yes|允许|同意|可以)$/i.test(text);
            session.approve(pending.eventId, allowed);
            ctx.session.emit(JSON.stringify({
                type: 'message',
                role: 'system',
                text: allowed ? '已允许一次' : '已拒绝',
            }));
        } else {
            session.answer(pending.eventId, 'q0', [text]);
            ctx.session.emit(JSON.stringify({
                type: 'message',
                role: 'system',
                text: `已回答：${text}`,
            }));
        }
        return { action: 'continue' };
    }

    lastAssistantText = '';
    if (!session.sendPrompt(text)) {
        return { action: 'stop', reason: '连接已断开' };
    }
    ctx.session.emit(JSON.stringify({ type: 'status', text: '正在回复', inputEnabled: false }));
    return { action: 'continue' };
}

export function sessionStop(): void {
    closeSession();
}

function closeSession(): void {
    const session = activeSession;
    activeSession = null;
    lastAssistantText = '';
    pendingApprovals.clear();
    if (session !== null) session.close();
}

function firstPending(): { eventId: string; kind: 'approval' | 'question' } | undefined {
    for (const [eventId, kind] of pendingApprovals) return { eventId, kind };
    return undefined;
}

// ---------------------------------------------------------------------------
// Host 发现
// ---------------------------------------------------------------------------

async function discoverHosts(): Promise<HostDevice[]> {
    const auth = loadAuth();
    const credentials = loadDeviceCredentials();
    if (auth === null || credentials === null) return [];
    try {
        return await listHostDevices(auth.serverUrl, credentials.deviceAccessToken);
    } catch (error) {
        ctx.log.w('dsh', `拉取电脑列表失败：${error instanceof Error ? error.message : String(error)}`);
        return [];
    }
}

function loadSelectedHost(): HostDevice | null {
    const raw = ctx.credentials.get(SLOT_HOST);
    if (typeof raw !== 'string' || raw.length === 0) return null;
    try {
        const parsed = JSON.parse(raw) as HostDevice;
        if (typeof parsed.deviceId !== 'string' || typeof parsed.identityKey !== 'string') return null;
        return parsed;
    } catch {
        return null;
    }
}

function saveSelectedHost(host: HostDevice): void {
    ctx.credentials.put(SLOT_HOST, JSON.stringify(host));
}
