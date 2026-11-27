// DSH 详情页的视图层：详情页树、按钮/选择器回传、会话清单的拉取与翻译。
//
// ## 为什么从 contract.ts 拆出来
//
// `contract.ts` 承担配对登录、凭据与 CA 恢复、会话四函数，本身已经接近单文件
// 上限；详情页渲染再塞进去会让「哪里是会话、哪里是界面」彻底混在一起。
// 拆开后职责很清楚：
//
// - `contract.ts`：谁在收发（配对、凭据、会话生命周期）
// - 本文件：界面长什么样、用户点了什么
//
// ## 状态怎么共享
//
// 会话语义相关的模块级变量仍在 `contract.ts` 里（它是会话的所有者），
// 本文件通过 `detailBridge` 读写，**不反向被 contract 依赖**——
// 这样依赖方向始终是「视图 → 会话」，不会形成循环。

import type { PickerCandidate, UiNode } from '../types/plugin-contract.js';
import type { SessionSummary } from './sessionWire.js';

/**
 * 视图层访问会话状态的窄接口。
 *
 * 用接口而不是直接 import 那些 `let` 变量：ESM 的导出绑定虽然对 `let` 是活的，
 * 但「视图层直接读会话变量」会让谁改了它变得难以追踪。收成读写函数后，
 * 每个字段只有一个写入点。
 */
export interface DetailBridge {
    /** 网关显示名（去掉 https://） */
    hostLabel(): string
    /** 是否已配对；未配对时详情页只提示去配对 */
    isPaired(): boolean
    /** 是否已建立订阅 */
    isConnected(): boolean
    /** 最近一次失败原因；null 表示没有 */
    lastFailure(): string | null
    setLastFailure(message: string | null): void
    /** 插件记的当前会话 id */
    selectedSessionId(): string
    setSelectedSessionId(id: string): void
    /** 宿主刚要求切到的 key */
    pendingSwitchKey(): string
    setPendingSwitchKey(key: string): void
    /** 会话清单缓存 */
    cachedSessions(): SessionSummary[]
    setCachedSessions(sessions: SessionSummary[]): void
    /** 拉一次清单（复用 contract 里的凭据链路） */
    fetchSessions(): Promise<SessionSummary[]>
    /** 数据源 key 的前缀换算 */
    sessionKeyOf(sessionId: string): string
    sessionIdOf(key: string): string
}

/**
 * 详情页：连接状态 + 会话选择 + 对话。
 *
 * ## 三块各自的职责
 *
 * 1. **选择器**（`picker`）：候选来自插件自己拉到的会话清单（静态候选），
 *    选中态由 `selectedIds` 回传。用户点一项 → 宿主回传该候选 id →
 *    下次 `detailUi` 里对话的 `binding` 就指向新的 key。这就是「切换历史对话」。
 * 2. **对话**（`chat`）：`session:<key>:messages`。用 `chat` 而不是
 *    `list` + `text` 是关键——角色、轮次、流式光标由宿主统一渲染，
 *    用户才能一眼分出「我说的」和「电脑说的」。
 * 3. **输入框**：`submitTo` 指向**当前会话**的 key，提交直接进会话控制器，
 *    不经过 `onAction`。
 *
 * ## key 从哪来
 *
 * 优先用 `pendingSwitchKey`（宿主刚告诉我们要切到哪条），否则用
 * `selectedSessionId`。两者都为空时仍渲染选择器——用户得能先选一条。
 */
export function buildDetailUi(bridge: DetailBridge): UiNode {
    if (!bridge.isPaired()) {
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
                            card: false,
                            body:
                                '请先在插件列表里完成配对（需要电脑端 dsh-mobile 生成的配对链接），' +
                                '再回到这里对话。',
                        },
                    ],
                },
            ],
        };
    }

    // 当前会话：宿主刚给的 key 优先，其次是插件自己记的
    const pendingKey = bridge.pendingSwitchKey();
    const currentId = pendingKey.length > 0
        ? bridge.sessionIdOf(pendingKey)
        : bridge.selectedSessionId();
    const currentKey = currentId.length > 0 ? bridge.sessionKeyOf(currentId) : '';

    const connected = bridge.isConnected();
    const header: UiNode[] = [
        {
            type: 'statusCard',
            id: 'conn',
            label: bridge.hostLabel(),
            value: connected ? '已连接' : '未连接',
            tone: connected ? 'ok' : 'neutral',
            detail: currentId.length > 0 ? `会话 ${currentId.slice(0, 8)}` : '尚未选择会话',
        },
    ];
    const failure = bridge.lastFailure();
    if (failure !== null) {
        header.push({
            type: 'text',
            card: false,
            emphasis: 'caption',
            tone: 'warn',
            body: `上次连接失败：${failure}`,
        });
    }

    const sessions = bridge.cachedSessions();

    // 清单为空时不给选择器：一个「暂无可选项」的按钮比没有更让人困惑
    const picker: UiNode[] = sessions.length > 0
        ? [{
            type: 'picker',
            id: 'sessionPicker',
            actionId: 'dsh.selectSession',
            title: '选择会话',
            mode: 'modal',
            selectedIds: currentId.length > 0 ? [bridge.sessionKeyOf(currentId)] : [],
            emptyHint: '点「刷新会话」拉取电脑上的对话',
            candidates: sessions.map(toCandidate(bridge)),
        }]
        : [{
            type: 'text',
            card: false,
            emphasis: 'caption',
            body: '还没有会话清单，点下方「刷新会话」。',
        }];

    const conversation: UiNode[] = currentKey.length > 0
        ? [
            {
                type: 'chat',
                id: 'messages',
                // 固定高度让对话区自己滚，输入框稳定在下方
                maxHeightDp: 360,
                selfName: '我',
                peerName: '电脑',
                emptyHint: '连接后在这里对话',
                messages: { binding: 'session:' + currentKey + ':messages' },
            },
            {
                type: 'text',
                id: 'sessionStatus',
                card: false,
                emphasis: 'caption',
                body: { binding: 'session:' + currentKey + ':status' },
            },
            {
                type: 'input',
                id: 'send',
                actionId: 'dsh.send',
                placeholder: '给电脑发指令…',
                submitLabel: '发送',
                submitTo: 'session:' + currentKey,
                enabled: { binding: 'session:' + currentKey + ':inputEnabled' },
            },
        ]
        : [{
            type: 'text',
            card: false,
            emphasis: 'caption',
            body: currentId.length > 0
                ? '正在连接会话…'
                : '先在上面选一个会话，或点「刷新会话」拉取清单。',
        }];

    return {
        type: 'column',
        children: [
            { type: 'column', card: true, id: 'status', children: header },
            ...picker,
            ...conversation,
            {
                type: 'row',
                id: 'actions',
                children: [
                    { type: 'button', actionId: 'dsh.refreshSessions', label: '刷新会话' },
                ],
            },
        ],
    };
}

/** 处理详情页上的按钮与选择器回传 */
export async function handleDetailAction(
    bridge: DetailBridge,
    actionId: string,
    value?: string,
): Promise<unknown> {
    if (actionId === 'dsh.refreshSessions') {
        const ok = await refreshSessionList(bridge);
        if (!ok) {
            return { type: 'showMessage', text: bridge.lastFailure() ?? '拉取会话清单失败' };
        }
        return { type: 'refreshUi', root: buildDetailUi(bridge) };
    }

    if (actionId === 'dsh.selectSession') {
        const key = (value ?? '').trim();
        const nextId = bridge.sessionIdOf(key);
        if (nextId.length === 0) {
            return { type: 'showMessage', text: '没能识别所选会话，请刷新列表后重试' };
        }
        ctx.log.i('dsh', `切换到会话 ${nextId.slice(0, 8)}`);
        // 只记「要切到哪一条」：真正的订阅由宿主的 sessionStart 驱动，
        // 它会带着这个 key 回来。这里若自己开订阅，会和宿主各开一条
        bridge.setPendingSwitchKey(key);
        bridge.setSelectedSessionId(nextId);
        return { type: 'refreshUi', root: buildDetailUi(bridge) };
    }

    return {
        type: 'showMessage',
        text: value === undefined ? '未知操作' : `已忽略：${actionId}`,
    };
}

/**
 * 拉一次会话清单并缓存。
 *
 * ## 为什么清单由插件自己拉
 *
 * DSH 的 `/sessions`、别家的 `/threads` 都是各家自己的协议，宿主不该懂。
 * 插件拉完、翻译成 `PickerCandidate` 交给选择器，宿主只负责渲染。
 *
 * 失败**不静默**：清单拉不到时用户会看到一个空选择器，那看起来像
 * 「电脑上没有会话」，而实际可能是配对失效了。所以这里把失败原因
 * 记进 `lastFailure`，由 `detailUi` 与 `health` 展示。
 *
 * @return 是否成功。网络失败保留最近缓存；成功的空列表才淘汰旧目标。
 */
export async function refreshSessionList(bridge: DetailBridge): Promise<boolean> {
    try {
        const sessions = await bridge.fetchSessions();
        bridge.setCachedSessions(collectVisibleSessions(sessions));
        bridge.setLastFailure(null);
        return true;
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        bridge.setLastFailure(message);
        ctx.log.w('dsh', `拉取会话清单失败：${message}`);
        return false;
    }
}

/**
 * 子代理会话不列（它们不是用户主动开的对话），其余按活动时间倒序。
 *
 * 排序放在这里而不是让宿主做：宿主不认识 `updatedAt` 的含义，
 * 也不该替插件决定「哪个会话更近」。
 */
export function collectVisibleSessions(sessions: SessionSummary[]): SessionSummary[] {
    return sessions
        .filter((s) => s.origin === undefined || s.origin !== 'subagent')
        .sort((a, b) => b.updatedAt - a.updatedAt);
}

/**
 * 把一条会话翻译成候选。
 *
 * ## 只给语义字段
 *
 * 宿主不允许插件自定义行外观，这里也只能给「叫什么、什么状态」。
 * `detail` 里**不得**放凭据、完整路径或 token——它会原样展示给用户。
 *
 * 不可发送的会话（`agentAvailable === false`）置灰而不是隐藏：
 * 用户需要知道「这条对话还在，只是电脑那边没开」。
 */
function toCandidate(bridge: DetailBridge): (session: SessionSummary) => PickerCandidate {
    return (session) => {
        const flags: string[] = [];
        if (session.running) flags.push('运行中');
        if (!session.agentAvailable) flags.push('电脑未在线');
        if (session.blank) flags.push('空会话');
        return {
            id: bridge.sessionKeyOf(session.sessionId),
            label: sessionLabel(session),
            detail: flags.join(' · '),
            tone: session.agentAvailable ? 'neutral' : 'warn',
            disabled: !session.agentAvailable,
        };
    };
}

/** 界面上的会话名：id 太长只取前 8 位，能用工作目录就带上目录名 */
export function sessionLabel(session: SessionSummary): string {
    const short = session.sessionId.slice(0, 8);
    return session.cwd !== undefined && session.cwd.length > 0
        ? cwdName(session.cwd) + ' · ' + short
        : short;
}

/** 取路径最后一段作为「这个会话在哪个目录」的提示 */
function cwdName(cwd: string): string {
    const parts = cwd.split(/[\\/]+/).filter((p) => p.length > 0);
    return parts.length > 0 ? parts[parts.length - 1] : cwd;
}
