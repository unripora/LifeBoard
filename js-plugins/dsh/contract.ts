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
import { Conversation } from './conversation.js';
// 具名类型从契约声明文件导入（它是模块）；裸 `ctx` 由 `../types/index.d.ts`
// 的全局声明提供，见该文件的说明。
import type { UiNode } from '../types/plugin-contract.js';
import {
    buildDetailUi,
    handleDetailAction,
    type DetailBridge,
} from './detailView.js';

/**
 * 会话 key 的**前缀**：每个 DSH 会话映射成一个独立的数据源 key。
 *
 * ## 为什么带前缀
 *
 * `session:<key>:*` 里的 `<key>` 由宿主原样交给会话控制器。DSH 的 sessionId
 * 是 64 位十六进制串，直接当 key 也能用，但界面里（诊断、日志、`detailUi`
 * 里的 `binding`）就完全看不出「这是哪家的会话」。前缀让 key 自解释，
 * 也避免了将来接入第二种会话型插件时的撞名风险。
 *
 * 一个插件只要用 `session:dsh-<id>:messages` 这种写法，宿主就按 key 隔离
 * 消息表——这就是「多个历史对话互不串」的实现方式，不需要任何会话专属字段。
 */
const SESSION_KEY_PREFIX = 'dsh-';

/**
 * 旧写法（首期单会话）用的固定 key `main`。
 *
 * 那份实现里所有消息都落在 `session:main:*`。现在每个 DSH 会话有自己的 key，
 * 旧 key 不会再被写入，也不需要对它做迁移——会话消息本来就不落库
 * （进程退出即清空），重新连接会用新 key 重新推历史。
 */

/** 构造某个 DSH 会话的数据源 key */
function sessionKeyOf(sessionId: string): string {
    return SESSION_KEY_PREFIX + sessionId;
}

/**
 * 从数据源 key 反解 DSH 会话 id。
 *
 * 认不出时返回空串：调用方据此走「还没选会话」的分支，
 * 而不是拿一个非法 id 去请求网关。
 */
function sessionIdOf(key: string): string {
    if (key.startsWith(SESSION_KEY_PREFIX)) return key.slice(SESSION_KEY_PREFIX.length);
    return '';
}

/** 当前订阅。模块级：跨调用存活 */
let activeFollow: { close(): void } | null = null;

/** 最近一次失败的原因，供 `sessionStart` 返回 false 后 `detailUi` 展示 */
let lastFailure: string | null = null;

/** 登录中间态：配对码与 CA 指纹只在这个变量里过一手，不落库 */
let pendingPairing: { gateway: string; label: string; code: string; instance: string } | null = null;

/** 已选中的会话 id（DSH 自己的 sessionId，不是数据源 key） */
let selectedSessionId = '';

/**
 * 最近一次「要切换到哪条会话」。
 *
 * ## 为什么不能只用 selectedSessionId
 *
 * 宿主的切换语义是：用户在 `picker` 里选了一项 → 宿主把 id 回传给
 * `onAction` → 插件下次 `detailUi` 里就能写出新的 `binding` key → 宿主看到
 * 新的 `session:<key>:*` 会再调一次 `sessionStart`（带 `options.sessionKey`）。
 *
 * 这条链路里 `sessionStart` 拿到的 key 才是**权威**的「现在该看哪一条」，
 * 插件自己记的 `selectedSessionId` 只负责渲染选中态。两者不一致时以
 * `sessionStart` 给的为准，否则用户点了 A 却看到 B 的消息。
 */
let pendingSwitchKey = '';

/**
 * 会话清单缓存：`sessionList:items` 的数据来源。
 *
 * 宿主的清单源是**推**模式：插件在 `detailUi` 被调用前把清单推上去，
 * 选择器才能渲染出候选。缓存住是因为候选要同时用于两处——
 * 渲染选择器、以及把用户选中的 id 翻译回会话元信息。
 */
let cachedSessions: SessionSummary[] = [];

/** 当前订阅的展示账本与运行态；running 初值来自 session/list。 */
let conversation: Conversation | null = null;
let running = false;

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
//
// 实现在 detailView.ts（界面怎么组织），这里只保留对外导出与会话状态的桥接。
//
// ## 为什么要有 bridge 而不是让 detailView 直接 import 状态
//
// 会话语义相关的变量（`selectedSessionId` / `pendingSwitchKey` /
// `cachedSessions` / `lastFailure` / `activeFollow`）都住在本文件——它是
// 会话的所有者。若让视图层直接读写这些 `let`，依赖方向就变成双向，
// 谁改了它难以追踪。bridge 把读写收成函数，每个字段因此只有一个写入点。

/** 视图层访问会话状态的实现（见 detailView.ts 的 DetailBridge） */
const detailBridge: DetailBridge = {
    hostLabel: () => hostLabel(loadPairingOrEmpty()),
    isPaired: () => loadPairing() !== null,
    isConnected: () => activeFollow !== null,
    lastFailure: () => lastFailure,
    setLastFailure: (message) => {
        lastFailure = message;
    },
    selectedSessionId: () => selectedSessionId,
    setSelectedSessionId: (id) => {
        selectedSessionId = id;
    },
    pendingSwitchKey: () => pendingSwitchKey,
    setPendingSwitchKey: (key) => {
        pendingSwitchKey = key;
    },
    cachedSessions: () => cachedSessions,
    setCachedSessions: applySessionList,
    fetchSessions: () => withSession((options) => listSessions(options)),
    sessionKeyOf,
    sessionIdOf,
};

/**
 * 详情页：连接状态 + 会话选择 + 对话。
 *
 * 具体结构见 [buildDetailUi]（detailView.ts）：候选来自插件自己拉到的
 * 会话清单，对话绑定到 `session:<key>:*`，输入框的 `submitTo` 指向当前会话。
 */
export function detailUi(): UiNode {
    return buildDetailUi(detailBridge);
}

/**
 * 详情页上的按钮与选择器回传。
 *
 * `dsh.selectSession` 只记「要切到哪一条」，真正的订阅由宿主的
 * `sessionStart`（带 `options.sessionKey`）驱动——那里才是唯一建立连接的地方。
 */
export async function onAction(actionId: string, value?: string): Promise<unknown> {
    return handleDetailAction(detailBridge, actionId, value);
}

/** 配对网关的显示名；未配对时给空串（此时不会用到） */
function loadPairingOrEmpty(): string {
    const pairing = loadPairing();
    return pairing === null ? '' : pairing.gateway;
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
 * 建立到电脑的会话，或**切换到另一条**会话。
 *
 * ## 切换 ≠ 重连
 *
 * 宿主在「用户选了另一条会话」时也会调这个函数，但**不重新握手**：
 * 配对凭据、CA 信任、Cookie 会话都沿用。所以这里做的是「断开旧的订阅、
 * 建立新的订阅」，与首次连接走同一条代码路径，唯一的区别是
 * `activeFollow !== null` 时先关掉旧的。
 *
 * 这正是「认证一次、多个历史对话」：认证属于连接，切换属于订阅。
 *
 * ## 目标会话从哪来
 *
 * 宿主的约定是 `options.sessionKey`（保留键，见 `SessionConfig` 的 KDoc），
 * 值是插件自己写进绑定的那个 key（`dsh-<sessionId>`）。拿不到时退回
 * 「上次选的，否则最近活动的那个」——这样旧宿主（不传 key）也能用。
 */
export async function sessionStart(options?: { sessionKey?: string }): Promise<boolean> {
    try {
        // 切会话时先关旧订阅：同一条连接上不能有两条 follow 同时推
        closeSession();
        lastFailure = null;

        if (loadPairing() === null) {
            lastFailure = '尚未配对';
            ctx.session.emit(JSON.stringify({ type: 'status', text: lastFailure, inputEnabled: false }));
            return false;
        }

        ctx.session.emit(JSON.stringify({ type: 'status', text: '正在连接…', inputEnabled: false }));

        const sessions = await withSession((c) => listSessions(c));
        // 缓存清单：用户下一次进详情页（或刷新）时，选择器要能直接列出来
        applySessionList(sessions);

        const usable = sessions.filter((s) => s.agentAvailable && !s.blank);
        const requestedId = sessionIdOf((options?.sessionKey ?? '').trim());
        const target = pickSession(usable, requestedId);
        if (target === undefined) {
            lastFailure = usable.length === 0
                ? '电脑上没有正在运行的会话，请先在电脑上打开一个对话'
                : '请先选择一个会话';
            ctx.session.emit(JSON.stringify({ type: 'status', text: lastFailure, inputEnabled: false }));
            return false;
        }
        selectedSessionId = target.sessionId;
        pendingSwitchKey = sessionKeyOf(target.sessionId);

        const pairing = loadPairing();
        if (pairing === null) return false;

        running = target.running;
        const display = new Conversation((event) => ctx.session.emit(JSON.stringify(event)));
        conversation = display;
        activeFollow = await followSession(pairing.gateway, target.sessionId, {
            onSnapshot(messages) {
                display.snapshot(messages);
                emitRunningStatus();
            },
            onTurnStart() {
                running = true;
                emitRunningStatus();
            },
            onAttemptReset() {
                display.resetAttempt();
            },
            onMessage(message) {
                display.message(message);
            },
            onDelta(text) {
                display.delta(text);
            },
            onError(message) {
                display.resetAttempt();
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
                display.resetAttempt();
                running = false;
                reportTurn('done');
                emitRunningStatus();
            },
            onClosed(reason) {
                closeSession();
                lastFailure = reason;
                ctx.session.emit(JSON.stringify({ type: 'closed', reason }));
            },
        });

        emitRunningStatus();
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

/**
 * 选出要订阅的会话。
 *
 * 优先级：
 * 1. 宿主给的 `requestedId`（用户在选择器里点了哪一条）
 * 2. 上次选中的（同一插件跨页面的连续体验）
 * 3. 最近活动的（`listSessions` 已按活动时间排序）
 *
 * 找不到时返回 `undefined`，由调用方给出明确提示——**不自动新建**：
 * 用户会困惑「为什么突然多了个会话」。
 */
function pickSession(
    sessions: SessionSummary[],
    requestedId: string,
): SessionSummary | undefined {
    if (requestedId.length > 0) {
        const picked = sessions.find((s) => s.sessionId === requestedId);
        if (picked !== undefined) return picked;
        // 明确指了一条但不可用：不静默换成别的，否则用户会以为切换成功了
        return undefined;
    }
    if (selectedSessionId.length > 0) {
        const kept = sessions.find((s) => s.sessionId === selectedSessionId);
        if (kept !== undefined) return kept;
    }
    return sessions[0];
}

function emitRunningStatus(): void {
    ctx.session.emit(JSON.stringify({
        type: 'status', text: running ? '电脑正在回复' : '已连接', inputEnabled: !running,
    }));
}

/** 仅成功 list 结果可淘汰缓存；目标消失时也撤掉旧绑定与订阅。 */
function applySessionList(sessions: SessionSummary[]): void {
    cachedSessions = sessions.filter((s) => s.origin !== 'subagent').sort((a, b) => b.updatedAt - a.updatedAt);
    const ids = new Set(cachedSessions.map((s) => s.sessionId));
    if (selectedSessionId.length > 0 && !ids.has(selectedSessionId)) {
        closeSession();
        selectedSessionId = '';
    }
    if (pendingSwitchKey.length > 0 && !ids.has(sessionIdOf(pendingSwitchKey))) pendingSwitchKey = '';
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

    const requestId = newRequestId();
    conversation?.submitted(requestId, text);
    running = true;
    emitRunningStatus();

    try {
        const sessionId = selectedSessionId;
        await withSession((options) => promptSession(options, sessionId, text, requestId));
        return { action: 'continue' };
    } catch (error) {
        const message = describeError(error);
        ctx.log.e('dsh', `发送失败：${message}`);
        emitMessage({ role: 'system', text: `发送失败：${message}` });
        // 发送失败不该断掉整条会话：恢复输入让用户重试
        running = false;
        emitRunningStatus();
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
    if ((conversation?.buffer.length ?? 0) > 0) reportTurn('interrupted');
    conversation = null;
    running = false;
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
