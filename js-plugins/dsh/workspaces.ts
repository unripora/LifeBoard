// 工作区（workspace）列表。
//
// 来源：https://github.com/liguobao/ds-harness-remote（MIT，提交 eb907b2）
// 参考：`packages/client-core/src/harness-alpha-client.ts` §`workspaceList` /
//       §`normalizeWorkspaceList` / §`HarnessWorkspaceView`
//
// ## 为什么不是 unary 调用
//
// 工作区是**跟随式**数据：有一个就绪基线（baseline），后续还有增删改帧。
// 上游 `workspaceList()` 的做法是打开 `workspace/follow` 流、只读首帧
// `{type:'baseline', value}` 然后立即关闭。首期照做：能列出工作区即可，
// 不做实时同步——工作区在会话页只在「新建会话」时选一次。
//
// ## 字段取舍
//
// `sessionIds` 保留：新建会话后可以据此判断该工作区是否已有会话。
// `createdAt` / `updatedAt` 是服务端字符串，原样保留用于排序。

/// <reference path="../types/index.d.ts" />

import { createStreamOpenRequest } from './stream.js';
import { ProtocolError } from './protocol.js';

/** 工作区跟随流 endpoint */
export const WORKSPACE_FOLLOW_ENDPOINT = 'workspace/follow';

export interface WorkspaceView {
    workspaceId: string;
    /** Host 上的绝对路径 */
    path: string;
    title: string;
    sessionIds: string[];
    createdAt?: string;
    updatedAt?: string;
}

export interface WorkspaceList {
    items: WorkspaceView[];
    archivedSessionIds: string[];
    pinnedSessionIds: string[];
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 打开工作区跟随流。
 *
 * args 为空对象：Host 不要求在 baseline 前提供参数。
 */
export function createWorkspaceFollowRequest(streamId: string): ReturnType<typeof createStreamOpenRequest> {
    return createStreamOpenRequest(streamId, WORKSPACE_FOLLOW_ENDPOINT, {});
}

/**
 * 从 `workspace/follow` 的首帧取出工作区列表。
 *
 * 首帧不是 `baseline` 说明两端对协议理解不一致，必须报错：
 * 继续读后续增量帧只会得到「少了几个工作区」的静默错误。
 */
export function readWorkspaceBaseline(frame: unknown): WorkspaceList {
    if (!isObject(frame) || frame.type !== 'baseline' || !isObject(frame.value)) {
        throw new ProtocolError('BAD_PAYLOAD', 'workspace/follow 首帧不是 baseline');
    }
    return readWorkspaceList(frame.value);
}

/** 解析 baseline 的业务值；坏条目丢弃，与 `session/list` 同一容错策略 */
export function readWorkspaceList(value: unknown): WorkspaceList {
    if (!isObject(value)) {
        return { items: [], archivedSessionIds: [], pinnedSessionIds: [] };
    }
    const items: WorkspaceView[] = [];
    if (Array.isArray(value.items)) {
        for (const item of value.items) {
            const workspace = readWorkspace(item);
            if (workspace !== undefined) items.push(workspace);
        }
    }
    return {
        items,
        archivedSessionIds: readStringArray(value.archivedSessionIds),
        pinnedSessionIds: readStringArray(value.pinnedSessionIds),
    };
}

function readWorkspace(value: unknown): WorkspaceView | undefined {
    if (!isObject(value)) return undefined;
    const workspaceId = value.workspaceId;
    if (typeof workspaceId !== 'string' || workspaceId.length === 0) return undefined;

    const workspace: WorkspaceView = {
        workspaceId,
        path: typeof value.path === 'string' ? value.path : '',
        title: typeof value.title === 'string' ? value.title : '',
        sessionIds: readStringArray(value.sessionIds),
    };
    if (typeof value.createdAt === 'string' && value.createdAt.length > 0) workspace.createdAt = value.createdAt;
    if (typeof value.updatedAt === 'string' && value.updatedAt.length > 0) workspace.updatedAt = value.updatedAt;
    return workspace;
}

function readStringArray(value: unknown): string[] {
    if (!Array.isArray(value)) return [];
    const out: string[] = [];
    for (const item of value) {
        if (typeof item === 'string' && item.length > 0) out.push(item);
    }
    return out;
}
