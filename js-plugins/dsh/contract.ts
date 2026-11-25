// DSH 插件的契约出口：配对登录、详情区块、会话四函数。
//
// 依赖：`gateway.ts`（CA 钉扎 / 配对 / Cookie 会话 / 续期）、
//       `sessionWire.ts`（列会话 / 发 prompt / 跟随流）。
//
// ## 状态全放模块变量
//
// 宿主每次调用都注入一个新的 `ctx`，只有模块变量能跨调用存活
// （见编写指南「中间态只能放模块变量」）。`activeFollow` 尤其如此：
// `sessionStart` 开的订阅必须在 `sessionEvent` / `sessionStop` 里还能拿到。
//
// ## 会话四函数与宿主的约定
//
// | 导出 | 何时调用 | 必须做什么 |
// |---|---|---|
// | `sessionStart` | 详情页出现 `conversation` 区块 | 建订阅；**显式** `return true` 才算成功 |
// | `sessionEvent` | 用户提交输入 | 把文本发给电脑；返回 `{action:'stop'}` 表示会话结束 |
// | `sessionStop` | 离开详情页/撤销信任 | 断订阅；可能被重复调用 |
// | `ctx.session.emit` | 会话期间任意时刻 | 把消息/状态推给界面 |
//
// ## 登录态只看本地槽位
//
// `authState` **不发网络请求**：详情页每次进入都会问一次，
// 让它联网等于每次进页都打一次网关。

/// <reference path="../types/index.d.ts" />

import {
    clearPairing,
    closeGatewaySession,
    GATEWAY_SESSION,
    GatewayError,
    loadPairing,
    normalizeGatewayUrl,
    openGatewaySession,
    pairDevice,
    parsePairingLink,
    pinGatewayCa,
    renewSession,
    savePairing,
    sessionNeedsRenew,
    type StoredPairing,
} from './gateway.js';
import {
    followSession,
    listSessions,
    promptSession,
    type FollowMessage,
    type SessionSummary,
} from './sessionWire.js';
import { RpcError, type RpcOptions } from './rpc.js';
import { MuxError } from './mux.js';
import type { UiNode } from '../types/index.js';

/** 详情页会话区块固定的 sessionId（首期单会话） */
const SESSION_ID = 'main';

/** 当前订阅。模块级：跨调用存活 */
let activeFollow: { close(): void } | null = null;

/** 最近一次失败的原因，供 `sessionStart` 返回 false 后 `detailUi` 展示 */
let lastFailure: string | null = null;

/** 登录中间态：配对码与 CA 指纹只在这个变量里过一手，不落库 */
let pendingPairing: { gateway: string; label: string; code: string; instance: string } | null = null;

/** 已选中的会话 id */
let selectedSessionId = '';

/** 当前助手这一轮的累积文本，用于把累计值换算成增量 */
let assistantBuffer = '';

/**
 * 本次运行时里已恢复过 CA 的网关。
 *
 * CA 信任是**宿主进程内、按插件运行时**的状态：运行时重建（重装插件、
 * 撤销信任后再信任、宿主重启）之后，`trustCa` 的成果不会跟着回来。
 * 而配对的凭据是落盘的——重启后界面仍显示「已登录」，第一次请求却
 * 直接 PKIX 失败，看起来像「配对莫名失效」。
 *
 * 所以每次运行时里第一次联网前都要重新装一次 CA。这个变量只用来避免
 * 每个请求都重复装，值为 `gateway`（同一台电脑的 CA 是同一张）。
 */
let caReadyFor: string | null = null;

/** CA 恢复去重：并发的首次请求共用同一次恢复，失败后允许重试 */
let caRestore: Promise<void> | null = null;

// ---------------------------------------------------------------------------
// 登录（配对）
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
 * 配对登录：网关地址 + 配对码 → 设备与会话凭据。
 *
 * 两轮：第一轮要地址与配对码，第二轮确认后真正配对。
 * 拆开是为了让用户看清「连的是哪台电脑、配对码有没有填错」——
 * 配对码一次性且只有 120 秒，一次填错就得重新生成，先确认再提交更省事。
 */
export async function login(inputs?: Record<string, string>): Promise<LoginResult> {
    const form = inputs ?? {};
    try {
        if (pendingPairing === null) {
            const rawCode = (form['pairingCode'] ?? '').trim();
            const rawGateway = (form['gateway'] ?? '').trim();
            if (rawGateway.length === 0 && rawCode.length === 0) {
                return {
                    type: 'needInput',
                    prompt: '连接电脑上的 DSH',
                    fields: [
                        {
                            name: 'gateway',
                            displayName: '网关地址',
                            secret: false,
                            hint: '一般留空即可：整条配对链接里已带地址，会自动识别',
                        },
                        {
                            name: 'pairingCode',
                            displayName: '配对链接',
                            secret: true,
                            hint: '把电脑端复制的**整条**链接粘进来（必须含 instance= 段）。只复制后面 43 位码不够——钉扎要用的 CA 指纹就在链接里',
                        },
                    ],
                };
            }
            // 链接里三样一次取齐：origin → 网关地址，instance → CA 指纹，token → 配对码
            const link = parsePairingLink(rawCode);
            const gatewaySource = rawGateway.length > 0 ? rawGateway : (link === null ? '' : link.gateway);
            if (gatewaySource.length === 0) {
                return { type: 'failure', message: '没识别出网关地址，请整条复制配对链接，或手动填地址' };
            }
            if (link === null) {
                return {
                    type: 'failure',
                    message: '配对链接不完整：缺少实例指纹（instance）。请整条复制电脑端给的链接，不要只复制后面那串码',
                };
            }
            pendingPairing = {
                gateway: normalizeGatewayUrl(gatewaySource),
                label: 'LifeBoard',
                code: link.token,
                instance: link.instance,
            };
            return await completePairing(pendingPairing);
        }

        const pending = pendingPairing;
        pendingPairing = null;
        return await completePairing(pending);
    } catch (error) {
        pendingPairing = null;
        const message = error instanceof Error ? error.message : String(error);
        ctx.log.e('dsh', `配对失败：${message}`);
        return { type: 'failure', message: `配对失败：${message}` };
    }
}

/**
 * 真正执行配对：钉扎 CA → 换设备凭据 → 落库。
 *
 * `instance` 是配对链接里的 CA 指纹（`instance=` 段）。它有两个用途：
 * 1. 交给 `pinGatewayCa` 做指纹比对——这是分发给多用户时唯一能用的指纹来源
 * 2. 与电脑返回的 `instanceId` 交叉核对——链接和电脑对不上说明连错了机器
 */
async function completePairing(
    input: { gateway: string; label: string; code: string; instance: string },
): Promise<LoginResult> {
    // 顺序不能反：取 CA 的那一刻它还没被信任
    await pinGatewayCa(input.gateway, input.instance);
    const paired = await pairDevice(input.gateway, input.code, input.label);
    // 双保险：指纹对上了，还要确认电脑自报的实例就是链接里说的那一台。
    // 只比指纹已经能挡住中间人；这一步挡的是「用户粘了 A 机器的链接、
    // 地址却手动填成 B 机器」这种配置错误。
    if (paired.instanceId.trim().toLowerCase() !== input.instance) {
        throw new GatewayError(
            0,
            'INSTANCE_MISMATCH',
            '电脑返回的实例与配对链接不一致：可能连到了另一台机器，请重新复制配对链接',
        );
    }
    const pairing: StoredPairing = {
        gateway: input.gateway,
        label: input.label,
        instanceId: paired.instanceId,
        deviceId: paired.deviceId,
        deviceToken: paired.deviceToken,
        deviceExpiresAt: paired.deviceExpiresAt,
        sessionToken: paired.sessionToken,
        csrfToken: paired.csrfToken,
        sessionExpiresAt: paired.sessionExpiresAt,
    };
    savePairing(pairing);
    openGatewaySession(pairing.gateway, pairing.sessionToken);
    // 配对时刚装过这台的 CA：记下它，避免进详情页后又白装一次。
    // 同时清掉恢复去重状态，保证之后若再走 `ensureGatewayCa` 是干净的。
    caReadyFor = pairing.gateway;
    caRestore = null;
    ctx.log.i('dsh', `配对成功，设备 ${paired.deviceId.slice(0, 8)}`);
    return { type: 'success' };
}

export function logout(): void {
    closeSession();
    closeGatewaySession();
    clearPairing();
    // CA 信任仍是宿主进程内的状态，但登出后不该被下一次配对「借用」：
    // 换一台电脑时指纹不同，重置后能保证重新走一次校验
    resetCaState();
    selectedSessionId = '';
    pendingPairing = null;
}

export function authState(): { state: 'loggedOut' } | { state: 'loggedIn'; accountName?: string } {
    const pairing = loadPairing();
    if (pairing === null) return { state: 'loggedOut' };
    // 只显示主机部分，不把完整地址与令牌带进 UI
    return { state: 'loggedIn', accountName: hostLabel(pairing.gateway) };
}

function hostLabel(gateway: string): string {
    return gateway.replace(/^https:\/\//, '');
}

// ---------------------------------------------------------------------------
// 详情页区块
// ---------------------------------------------------------------------------

/**
 * 详情页：连接状态 + 对话。
 *
 * ## 对话为什么是「自己拼」的
 *
 * 宿主已没有专用的会话区块。对话 = 消息列表 + 输入框，两者用普通原语声明，
 * 消息与可用状态通过 `binding` 交给宿主托管：
 * - `session:main:messages` → 消息列表（会话控制器持续推送）
 * - `session:main:status` → 连接状态文案
 * - `session:main:inputEnabled` → 输入框是否可发
 *
 * 输入框的 `submitTo` 让提交直接投给会话控制器，不经过 `onAction`——
 * 那是一次插件调用，与「往常驻会话里发一句话」是两件事。
 */
export function detailUi(): UiNode {
    const pairing = loadPairing();

    if (pairing === null) {
        return {
            type: 'column',
            children: [
                {
                    type: 'column',
                    card: true,
                    title: '还没有连接电脑',
                    children: [
                        {
                            type: 'text',
                            body: '请先在插件列表里完成配对（需要电脑端 dsh-mobile 生成的配对链接），再回到这里对话。',
                        },
                    ],
                },
            ],
        };
    }

    const header: UiNode[] = [
        {
            type: 'statusCard',
            id: 'conn',
            label: hostLabel(pairing.gateway),
            value: activeFollow !== null ? '已连接' : '未连接',
            tone: activeFollow !== null ? 'ok' : 'neutral',
            detail: selectedSessionId.length > 0 ? `会话 ${selectedSessionId.slice(0, 8)}` : '尚未选择会话',
        },
    ];
    if (lastFailure !== null) {
        header.push({
            type: 'text',
            emphasis: 'caption',
            tone: 'warn',
            body: `上次连接失败：${lastFailure}`,
        });
    }

    return {
        type: 'column',
        children: [
            { type: 'column', card: true, id: 'status', children: header },
            {
                type: 'list',
                id: 'messages',
                // 固定高度让消息区自己滚，输入框稳定在下方
                maxHeightDp: 320,
                emptyHint: '连接后在这里对话',
                items: { binding: 'session:' + SESSION_ID + ':messages' },
            },
            {
                type: 'text',
                id: 'sessionStatus',
                emphasis: 'caption',
                body: { binding: 'session:' + SESSION_ID + ':status' },
            },
            {
                type: 'input',
                id: 'send',
                actionId: 'dsh.send',
                placeholder: '给电脑发指令…',
                submitLabel: '发送',
                submitTo: 'session:' + SESSION_ID,
                enabled: { binding: 'session:' + SESSION_ID + ':inputEnabled' },
            },
        ],
    };
}

export async function onAction(actionId: string, value?: string): Promise<unknown> {
    if (actionId === 'dsh.refreshSessions') {
        const sessions = await withSession((options) => listSessions(options));
        if (sessions.length === 0) return { type: 'showMessage', text: '电脑上没有可用会话' };
        return { type: 'showMessage', text: `发现 ${sessions.length} 个会话` };
    }
    return { type: 'showMessage', text: value === undefined ? '未知操作' : `已忽略：${actionId}` };
}

export function health(): { state: 'healthy' } | { state: 'degraded'; message: string } {
    if (lastFailure !== null) return { state: 'degraded', message: lastFailure };
    return { state: 'healthy' };
}

/**
 * 采集入口。
 *
 * DSH 不是数据源，没有指标要上报：返回空数组是**正确**语义。
 */
export function fetch(): unknown[] {
    return [];
}

/**
 * 保证有一个可用的会话凭据，然后执行一次 RPC。
 *
 * ## 顺序不能变
 *
 * 1. **恢复 CA**：宿主侧 CA 信任只活在当前运行时里，重启或重装插件后必须重装，
 *    否则第一次请求就 PKIX 失败（续期同样是 HTTPS 请求，不能排在它后面）。
 * 2. **恢复 Cookie 会话**：`sessionToken` 存在凭据槽里，但它对应的
 *    `CookieJar` 是内存状态。新运行时里 jar 是空的，不重新种 cookie
 *    就会带着一份「看起来有效」的 token 去请求，服务端只看到匿名请求。
 * 3. **按需续期**：TTL 到期前 5 分钟续一次，续完要把新 token 同步回 cookie。
 *
 * 会话 TTL 默认 8 小时，到期前 5 分钟自动续期一次。续期失败不静默吞掉：
 * 让上层拿到明确错误，用户才知道该重新配对。
 */
async function withSession<T>(run: (options: RpcOptions) => Promise<T>): Promise<T> {
    const pairing = loadPairing();
    if (pairing === null) throw new Error('尚未配对');

    // 1. 恢复 CA 信任（每个运行时对每台网关只装一次）
    await ensureGatewayCa(pairing.gateway, pairing.instanceId);

    // 2. 恢复 HTTP 会话：token 没变也要重种 cookie，运行时重建后 jar 是空的
    let current = pairing;
    if (sessionNeedsRenew(pairing.sessionExpiresAt)) {
        ctx.log.i('dsh', '会话即将过期，正在续期');
        const renewed = await renewSession(pairing.gateway, pairing.deviceToken);
        current = {
            ...pairing,
            sessionToken: renewed.sessionToken,
            csrfToken: renewed.csrfToken,
            sessionExpiresAt: renewed.sessionExpiresAt,
        };
        savePairing(current);
    }
    openGatewaySession(current.gateway, current.sessionToken);

    try {
        return await run({
            gateway: current.gateway,
            session: GATEWAY_SESSION,
            // 业务 RPC 全是 POST，网关要求写请求带 CSRF；漏了会被 403 挡在
            // 「来源或 Origin 校验未通过」这条文案后面，排查方向完全被带偏
            csrf: current.csrfToken,
        });
    } catch (error) {
        // 401 说明会话已被服务端作废（比如电脑端撤销了设备），
        // 这时只能重新配对，不该让调用方以为是网络问题
        if (error instanceof RpcError && (error.code === 'http/401' || error.status === 401)) {
            throw new Error('配对已失效，请在电脑端重新生成配对码后重新连接');
        }
        throw error;
    }
}

/**
 * 在当前运行时里装上网关 CA；已装过就直接返回。
 *
 * 指纹用配对照存下来的 `instanceId`——它是配对链接里的 `instance=`，
 * 也就是电脑自建 CA 的指纹。重新下载的证书必须与之相符，否则说明
 * 那台电脑换了 CA（重装 dsh-mobile、换机），应提示重新配对而不是静默信任。
 */
async function ensureGatewayCa(gateway: string, instanceId: string): Promise<void> {
    if (caReadyFor === gateway) return;
    if (caRestore !== null) return caRestore;

    const pending = (async () => {
        if (instanceId.trim().length !== 64) {
            throw new Error('配对照存里缺少 CA 指纹，请重新配对');
        }
        try {
            await pinGatewayCa(gateway, instanceId);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            // 指纹不符是配置层面的确定失败，重试没有意义，直接要求重新配对
            if (message.includes('CA 指纹不符')) {
                throw new Error('电脑的证书已变化（可能重装过 dsh-mobile），请重新配对');
            }
            throw error;
        }
        caReadyFor = gateway;
    })();

    caRestore = pending;
    try {
        await pending;
    } finally {
        // 失败时清空，让下一次请求还能再试；成功时 caReadyFor 已经置位
        if (caRestore === pending) caRestore = null;
    }
}

/** 配对/登出时清掉 CA 恢复状态：换了电脑就得重新装信任 */
function resetCaState(): void {
    caReadyFor = null;
    caRestore = null;
}

// ---------------------------------------------------------------------------
// 会话四函数
// ---------------------------------------------------------------------------

/**
 * 建立到电脑的会话。
 *
 * 首期只订阅**已存在且在线的**会话：没有可用会话时明确报错并提示，
 * 而不是悄悄建一个新的——用户会困惑「为什么突然多了个会话」。
 */
export async function sessionStart(): Promise<boolean> {
    try {
        closeSession();
        lastFailure = null;

        if (loadPairing() === null) {
            lastFailure = '尚未配对';
            ctx.session.emit(JSON.stringify({ type: 'status', text: lastFailure, inputEnabled: false }));
            return false;
        }

        ctx.session.emit(JSON.stringify({ type: 'status', text: '正在连接…', inputEnabled: false }));

        const sessions = await withSession((options) => listSessions(options));
        const usable = sessions.filter((s) => s.agentAvailable && !s.blank);
        const target = pickSession(usable);
        if (target === undefined) {
            lastFailure = usable.length === 0
                ? '电脑上没有正在运行的会话，请先在电脑上打开一个对话'
                : '请先选择一个会话';
            ctx.session.emit(JSON.stringify({ type: 'status', text: lastFailure, inputEnabled: false }));
            return false;
        }
        selectedSessionId = target.sessionId;

        const pairing = loadPairing();
        if (pairing === null) return false;

        activeFollow = await followSession(pairing.gateway, target.sessionId, {
            onSnapshot(messages, running) {
                ctx.session.emit(JSON.stringify({ type: 'clear' }));
                for (const message of messages) emitMessage(message);
                assistantBuffer = '';
                ctx.session.emit(JSON.stringify({
                    type: 'status',
                    text: running ? '电脑正在回复' : '已连接',
                    inputEnabled: !running,
                }));
            },
            onMessage(message) {
                // 助手消息会**先流式展示、后落库**，两条路径都发就会在气泡里
                // 出现两遍。落库文本与流式累积一致时跳过，只作定稿确认；
                // 不一致（例如流式丢帧）则照发，用落库的完整文本补全。
                if (message.role === 'assistant' && assistantBuffer.length > 0) {
                    const streamed = assistantBuffer.trim();
                    assistantBuffer = '';
                    if (message.text.trim() === streamed) return;
                }
                emitMessage(message);
            },
            onDelta(text) {
                // 宿主的 `delta` 是累加语义，所以这里发增量
                assistantBuffer += text;
                if (text.length > 0) {
                    ctx.session.emit(JSON.stringify({ type: 'delta', text }));
                }
            },
            onError(message) {
                assistantBuffer = '';
                ctx.log.w('dsh', `本轮失败：${message}`);
                reportTurn('failed');
                // 错误**落成一条系统消息**而不是一闪而过的状态条：
                // 用户要能回看「为什么这条没有回复」。实测最常见的是
                // 模型 provider 503，服务端重试 5 次仍失败才会走到这里。
                ctx.session.emit(JSON.stringify({
                    type: 'message',
                    role: 'system',
                    text: `本轮失败：${message}`,
                }));
            },
            onTurnEnd() {
                assistantBuffer = '';
                reportTurn('done');
                ctx.session.emit(JSON.stringify({ type: 'status', text: '已连接', inputEnabled: true }));
            },
            onClosed(reason) {
                closeSession();
                lastFailure = reason;
                ctx.session.emit(JSON.stringify({ type: 'closed', reason }));
            },
        });

        ctx.session.emit(JSON.stringify({
            type: 'status',
            text: `已连接 ${hostLabel(pairing.gateway)}`,
            inputEnabled: true,
        }));
        return true;
    } catch (error) {
        const message = describeError(error);
        lastFailure = message;
        ctx.log.e('dsh', `连接失败：${message}`);
        ctx.session.emit(JSON.stringify({ type: 'status', text: message, inputEnabled: false }));
        closeSession();
        return false;
    }
}

/** 优先用上次选中的会话，否则取最近活动的那个 */
function pickSession(sessions: SessionSummary[]): SessionSummary | undefined {
    if (selectedSessionId.length > 0) {
        const kept = sessions.find((s) => s.sessionId === selectedSessionId);
        if (kept !== undefined) return kept;
    }
    return sessions[0];
}

function emitMessage(message: FollowMessage): void {
    ctx.session.emit(JSON.stringify({ type: 'message', role: message.role, text: message.text }));
}

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
 * 空文本不发给电脑：服务端会以「内容为空」拒绝，而用户看到的只是没反应。
 */
export async function sessionEvent(event: SessionEventInput): Promise<SessionActionResult> {
    if (activeFollow === null || selectedSessionId.length === 0) {
        return { action: 'stop', reason: '连接已断开' };
    }

    const text = (event.payload ?? '').trim();
    if (text.length === 0) return { action: 'continue' };

    assistantBuffer = '';
    // 先把自己发的消息显示出来：等 round-trip 再显示会有明显延迟感
    emitMessage({ role: 'user', text });
    ctx.session.emit(JSON.stringify({ type: 'status', text: '正在回复', inputEnabled: false }));

    try {
        const sessionId = selectedSessionId;
        await withSession((options) => promptSession(options, sessionId, text, newRequestId()));
        return { action: 'continue' };
    } catch (error) {
        const message = describeError(error);
        ctx.log.e('dsh', `发送失败：${message}`);
        emitMessage({ role: 'system', text: `发送失败：${message}` });
        // 发送失败不该断掉整条会话：恢复输入让用户重试
        ctx.session.emit(JSON.stringify({ type: 'status', text: '已连接', inputEnabled: true }));
        return { action: 'continue' };
    }
}

export function sessionStop(): void {
    closeSession();
}

function closeSession(): void {
    const follow = activeFollow;
    activeFollow = null;
    // 回复收到一半就被停掉 → 记为「中断」。这条必须在清空缓冲前判断：
    // 缓冲非空意味着这一轮还没结束，用户是主动打断的
    if (assistantBuffer.length > 0) reportTurn('interrupted');
    assistantBuffer = '';
    if (follow !== null) follow.close();
}

/**
 * 上报一轮对话的结果。
 *
 * 三件事分开计：完成、中断、失败。分开才有意义——「今天完成 20 轮」
 * 和「其中 8 轮失败」是两种完全不同的使用体验，合成一个「总轮数」看不出来。
 *
 * 指标写入失败绝不影响对话：这是观测性调用，出问题只记日志。
 */
function reportTurn(outcome: 'done' | 'interrupted' | 'failed'): void {
    const key =
        outcome === 'done'
            ? 'TURN_DONE'
            : outcome === 'interrupted'
              ? 'TURN_INTERRUPTED'
              : 'TURN_FAILED';
    try {
        ctx.report(key, 1);
    } catch (e) {
        ctx.log.w('dsh', `指标上报失败（${key}）：${describeError(e)}`);
    }
}

let requestSeq = 0;

function newRequestId(): string {
    requestSeq += 1;
    return `lb-${Date.now().toString(36)}-${requestSeq}`;
}

/** 把各类失败翻成用户能行动的一句话 */
function describeError(error: unknown): string {
    if (error instanceof MuxError) {
        if (error.code === 'closed') return '连接已断开，请重新进入会话';
        if (error.code === 'ended') return '会话已结束';
        return `会话出错：${error.message}`;
    }
    if (error instanceof RpcError) return error.message;
    return error instanceof Error ? error.message : String(error);
}
