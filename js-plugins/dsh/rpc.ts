// 一元 RPC：`POST /api/<endpoint>`，请求与响应都是带 `rpcId` 的信封。
//
// 依据：`deepseek-ai/deepseek-harness` 的
//   `packages/client/connection/src/rpc.ts`（`ClientRequest` / `ServerResponse` / `ConnectionRpcResult`）
//
// ## 为什么一元调用不走 WebSocket
//
// mux 是**流**通道，适合订阅；但「列会话」「发一条 prompt」这类一次问一次答
// 的调用，用 HTTP 更直接——不必自己管 streamId、不必等 end 帧。
// DSH 前端就是这么分的：流走 mux，一元走 `POST /api/<endpoint>`。
//
// ## rpcId 必须双向校验
//
// 发起方生成、响应方原样回显。不校验的话，两个并发请求的响应错配会表现为
// 「数据串了」，而且只在并发时偶发——这类 bug 极难定位。
// 所以 [rpcCall] 主动比对，不一致直接报错。
//
// ## 4xx 不 reject 但仍是失败
//
// 宿主约定 `ctx.http` 对 4xx/5xx 不 reject（让插件能读错误体）。这里要把
// HTTP 层失败与业务层 `{ok:false}` 分开处理：前者是「没拿到响应」，
// 后者是「服务端明确拒绝」，两者的排查方向和用户提示都不同。

/// <reference path="../types/index.d.ts" />

import { CSRF_HEADER } from './gateway';

/** 服务端返回的业务失败 */
export interface RpcFailure {
    code: string;
    message: string;
    details: Record<string, unknown>;
}

export class RpcError extends Error {
    constructor(
        readonly code: string,
        message: string,
        readonly details: Record<string, unknown> = {},
        readonly status = 0,
    ) {
        super(message);
    }
}

/** 一次一元调用的可选项 */
export interface RpcOptions {
    /** 网关源，形如 `https://192.168.1.10:3443` */
    gateway: string;
    /** `openGatewaySession` 返回的会话名：Cookie 与 Origin 都挂在它上面 */
    session: string;
    /**
     * 写请求的 CSRF token（配对 / 续期响应里给的）。
     *
     * 网关对**所有非 GET** 的 `/api/*` 都校验 `x-dsh-mobile-csrf`
     * （`gateway.ts` 的 `requireCsrf`），缺了直接 403。一元 RPC 全是 POST，
     * 所以每次都必须带。
     *
     * 早期这里漏了：配对能成功，一进详情页就报「网关拒绝了本次请求
     * （来源或 Origin 校验未通过）」——文案指向 Origin，实际是缺 CSRF。
     */
    csrf?: string;
    timeoutMs?: number;
}

/**
 * 调一个 DSH 一元端点。
 *
 * ## `payload` 必须恰好是 `{args: {...}}`
 *
 * 网关的 `remoteRequest` 要求 payload **只有一个键 `args`**，且它是普通对象：
 *
 * ```
 * if (Reflect.ownKeys(payload).length !== 1 || !Object.hasOwn(payload, 'args'))
 *     throw new Error('Remote payload must contain exactly one plain-object args field')
 * ```
 *
 * 所以方法参数要包在 `args` 里，**不能**直接把 `{sessionId, content}` 当 payload。
 * 这一点在源码里不显眼，但错了整条调用都会被 500 拒掉。
 *
 * @param endpoint 形如 `session/list`、`session/prompt`（不含 `/api` 前缀）
 * @param args 该端点自己的参数，会被包进 `{args}`
 * @returns 成功时的 `result.value`
 * @throws [RpcError] 传输失败或业务失败
 */
export async function rpcCall<T>(
    options: RpcOptions,
    endpoint: string,
    args: Record<string, unknown>,
): Promise<T> {
    const rpcId = newRpcId();
    const response = await ctx.http.request(
        'POST',
        `${options.gateway}/api/${endpoint}`,
        JSON.stringify({
            type: 'client-request',
            rpcId,
            method: endpoint,
            // 恰好一个键：多一个键网关就整条拒绝
            payload: { args },
        }),
        {
            headers: {
                accept: 'application/json',
                ...(options.csrf === undefined ? {} : { [CSRF_HEADER]: options.csrf }),
            },
            contentType: 'application/json; charset=utf-8',
            timeoutMs: options.timeoutMs ?? 20000,
            // prompt 会写入会话日志、create 会建 Agent，重发都可能产生重复副作用
            retry: false,
        },
        options.session,
    );

    if (!response.ok) {
        throw new RpcError(
            `http/${response.status}`,
            describeHttpFailure(endpoint, response.status),
            {},
            response.status,
        );
    }

    const envelope = response.json as Record<string, unknown> | null;
    if (envelope === null || typeof envelope !== 'object') {
        throw new RpcError('bad/envelope', `${endpoint}：响应不是 JSON 对象`, {}, response.status);
    }
    if (envelope.type !== 'server-response') {
        throw new RpcError('bad/envelope', `${endpoint}：响应信封 type 不是 server-response`, {}, response.status);
    }
    // 并发下响应错配的唯一定位手段
    if (envelope.rpcId !== rpcId) {
        throw new RpcError(
            'bad/rpcId',
            `${endpoint}：rpcId 不匹配（发出 ${rpcId}，收到 ${String(envelope.rpcId)}）`,
            {},
            response.status,
        );
    }

    const result = envelope.result;
    if (typeof result !== 'object' || result === null || Array.isArray(result)) {
        throw new RpcError('bad/result', `${endpoint}：响应缺少 result`, {}, response.status);
    }
    const r = result as Record<string, unknown>;
    if (r.ok === true) return r.value as T;

    const error = r.error;
    if (typeof error === 'object' && error !== null && !Array.isArray(error)) {
        const e = error as Record<string, unknown>;
        throw new RpcError(
            typeof e.code === 'string' ? e.code : 'unknown',
            typeof e.message === 'string' ? e.message : `${endpoint} 失败`,
            typeof e.details === 'object' && e.details !== null && !Array.isArray(e.details)
                ? e.details as Record<string, unknown>
                : {},
            response.status,
        );
    }
    throw new RpcError('unknown', `${endpoint} 失败（无错误详情）`, {}, response.status);
}

/**
 * 把 HTTP 层失败翻成能行动的话。
 *
 * 这里只处理「压根没拿到业务响应」的情况，所以文案聚焦在连接与鉴权上：
 * 业务错误由上面的 `{ok:false}` 分支给出，两边不重叠。
 */
function describeHttpFailure(endpoint: string, status: number): string {
    if (status === 401) return '会话已失效，请重新配对';
    if (status === 403) return '网关拒绝了本次请求（来源或 Origin 校验未通过）';
    if (status === 404) return `电脑上没有 ${endpoint} 这个接口，请确认 DSH 版本`;
    if (status === 415) return '请求格式不被接受（需要 application/json）';
    if (status === 503) return '电脑上的 DSH 暂时不可用，请稍后重试';
    return `${endpoint} 请求失败（HTTP ${status}）`;
}

/** rpcId 生成器：只需在本机唯一，用时间戳 + 随机避免碰撞 */
let rpcSeq = 0;

function newRpcId(): string {
    rpcSeq += 1;
    return `lb-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e9).toString(36)}-${rpcSeq}`;
}
