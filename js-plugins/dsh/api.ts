// Server 的 REST 接入：账号登录、设备注册、token 刷新。
//
// 依据：上游 `docs/plugin-integration.md`（提交 eb907b2）
//   §2 Server 地址规范、§3.1 邮箱密码登录、§5.3 Client 注册、§6 刷新
//
// ## 本插件是 client 角色
//
// LifeBoard 扮演的是「客户端」：注册 role=client 的设备，连上 Server 后请求
// 连接用户的 Host（`connect.request`），再由 Host 执行任务。
// 因此这里禁止上报 `harnessVersion`——那是 Host 才有的字段（§5.3）。
//
// ## 凭证怎么放
//
// 账号 token 只用于注册与账号接口，**不进 WebSocket**（§1）；
// 设备 token pair 才是常驻连接凭据。两者都存进宿主 `ctx.credentials`，
// 私钥与 token 不写日志、不进 UI 状态。
//
// ## 为什么不复用 ctx.http 的会话 cookie
//
// Server 全走 `Authorization: Bearer`，不用 cookie。用默认会话即可，
// 这样也不会因为多个 Server 地址共用会话而串味。

/// <reference path="../types/index.d.ts" />

/** 上游默认 Server（§2） */
export const DEFAULT_SERVER_URL = 'https://dsh.r2049.cn';

/** 设备角色。LifeBoard 是客户端，不是 Harness Host */
export const DEVICE_ROLE = 'client' as const;

export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
    ) {
        super(message);
    }
}

/**
 * 规范化 Server 地址（§2）。
 *
 * 规则：必须是 https（开发模式除外）、不许带路径/查询/锚点、不许带用户名密码、末尾 `/` 去掉。
 * 这些不是洁癖——`{serverUrl}/api/v1` 是字符串拼接，多一个斜杠或带个路径
 * 就会请求到错误的位置，而错误信息通常只有一个 404。
 *
 * 手写解析而不是 `new URL()`：QuickJS 没有这个构造函数（见 `types/ctx.d.ts` 的能力表），
 * 而这里只需要「scheme + host:port」两段，用正则比补一个 polyfill 更容易看清边界。
 */
export function normalizeServerUrl(input: string, allowInsecure = false): string {
    const raw = (input ?? '').trim();
    if (raw.length === 0) throw new ApiError(0, 'SERVER_URL_EMPTY', 'Server 地址不能为空');

    const match = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([/?#].*)?$/.exec(raw);
    if (match === null) {
        throw new ApiError(0, 'SERVER_URL_INVALID', `Server 地址不合法：${raw}`);
    }
    const scheme = match[1].toLowerCase();
    const authority = match[2];
    const rest = match[3];

    if (scheme !== 'https' && !(allowInsecure && scheme === 'http')) {
        throw new ApiError(0, 'SERVER_URL_INSECURE', 'Server 必须使用 HTTPS');
    }
    // 只允许一个「根路径」，其余一律视为带路径
    if (rest !== undefined && rest !== '/' && rest.length > 0) {
        throw new ApiError(0, 'SERVER_URL_INVALID', 'Server 地址只能是源（scheme://host[:port]），不要带路径');
    }
    if (authority.length === 0) {
        throw new ApiError(0, 'SERVER_URL_INVALID', 'Server 地址缺少主机名');
    }
    if (authority.includes('@')) {
        throw new ApiError(0, 'SERVER_URL_INVALID', 'Server 地址不能包含用户名或密码');
    }
    // host[:port]：不允许空标签、不允许控制字符与空白
    if (!/^[A-Za-z0-9._-]+(:\d{1,5})?$/.test(authority) && !/^\[[0-9A-Fa-f:.]+\](:\d{1,5})?$/.test(authority)) {
        throw new ApiError(0, 'SERVER_URL_INVALID', `Server 主机名不合法：${authority}`);
    }
    return `${scheme}://${authority}`;
}

/** 从 HTTPS 源派生 WebSocket 地址（§7） */
export function deriveWsUrl(serverUrl: string): string {
    // 入参必须已经规范化过：这里再解析一次只会让两处规则各写一半
    const match = /^(https?):\/\/(.+)$/.exec(serverUrl);
    if (match === null) {
        throw new ApiError(0, 'SERVER_URL_INVALID', `Server 地址未规范化：${serverUrl}`);
    }
    const scheme = match[1] === 'https' ? 'wss' : 'ws';
    return `${scheme}://${match[2]}/ws/v1/connect`;
}

interface RequestOptions {
    method: 'GET' | 'POST';
    path: string;
    body?: unknown;
    bearer?: string;
    timeoutMs?: number;
}

/**
 * 发一次 JSON 请求。
 *
 * 4xx / 5xx 不 reject（宿主约定），所以这里自己读 `status` 并翻成 [ApiError]。
 * 错误码优先取响应体的 `code`，取不到用状态码兜底——上层靠 `code`
 * 决定「重登」「刷新」「别重试」，只看数字分不出 `TOKEN_EXPIRED` 和 `AUTH_INVALID`。
 */
async function requestJson<T>(serverUrl: string, options: RequestOptions): Promise<T> {
    const headers: Record<string, string> = { accept: 'application/json' };
    if (options.bearer !== undefined) headers.authorization = `Bearer ${options.bearer}`;

    const response = await ctx.http.request(
        options.method,
        `${serverUrl}${options.path}`,
        options.body === undefined ? null : JSON.stringify(options.body),
        {
            headers,
            contentType: 'application/json; charset=utf-8',
            timeoutMs: options.timeoutMs ?? 20000,
            // 这些接口都不是幂等语义（注册会建设备、刷新会轮换 token），禁止宿主自动重试
            retry: false,
        },
    );

    const payload = response.json as Record<string, unknown> | null;
    if (response.status >= 200 && response.status < 300) {
        if (payload === null || typeof payload !== 'object') {
            throw new ApiError(response.status, 'BAD_RESPONSE', 'Server 返回的不是 JSON 对象');
        }
        return payload as T;
    }

    const code = typeof payload?.code === 'string' ? payload.code : `HTTP_${response.status}`;
    const message = typeof payload?.message === 'string' && payload.message.length > 0
        ? payload.message
        : `请求失败（HTTP ${response.status}）`;
    throw new ApiError(response.status, code, message);
}

export interface AccountSession {
    token: string;
    expiresAt: number;
    account: string;
}

/** 邮箱密码登录（§3.1）。返回的 `token` 只是临时 accountToken，用完即弃 */
export async function loginWithPassword(
    serverUrl: string,
    email: string,
    password: string,
): Promise<AccountSession> {
    const body = await requestJson<Record<string, unknown>>(serverUrl, {
        method: 'POST',
        path: '/api/v1/auth/login',
        body: { email, password },
    });
    const token = body.token;
    if (typeof token !== 'string' || token.length === 0) {
        throw new ApiError(0, 'BAD_RESPONSE', '登录响应缺少 token');
    }
    return {
        token,
        expiresAt: typeof body.expiresAt === 'number' ? body.expiresAt : 0,
        account: typeof body.account === 'string' ? body.account : email,
    };
}

/** 设备凭证（§6 的保存结构，仅取服务端返回的四个字段） */
export interface DeviceCredentials {
    deviceAccessToken: string;
    deviceAccessTokenExpiresAt: number;
    deviceRefreshToken: string;
    deviceRefreshTokenExpiresAt: number;
}

export interface DeviceDescriptor {
    deviceId: string;
    name: string;
    platform: string;
    identityKey: string;
    clientVersion: string;
}

function readCredentials(body: Record<string, unknown>): DeviceCredentials {
    const access = body.accessToken;
    const refresh = body.refreshToken;
    if (typeof access !== 'string' || access.length === 0) {
        throw new ApiError(0, 'BAD_RESPONSE', '注册响应缺少 accessToken');
    }
    if (typeof refresh !== 'string' || refresh.length === 0) {
        throw new ApiError(0, 'BAD_RESPONSE', '注册响应缺少 refreshToken');
    }
    return {
        deviceAccessToken: access,
        deviceAccessTokenExpiresAt: typeof body.accessTokenExpiresAt === 'number'
            ? body.accessTokenExpiresAt
            : 0,
        deviceRefreshToken: refresh,
        deviceRefreshTokenExpiresAt: typeof body.refreshTokenExpiresAt === 'number'
            ? body.refreshTokenExpiresAt
            : 0,
    };
}

/**
 * 注册本机为 client 设备（§5.3）。
 *
 * 失败码里有两个必须区分对待：
 * - `ACCOUNT_AUTH_REQUIRED`：accountToken 失效，需要重新登录；
 * - `DEVICE_OWNERSHIP_REQUIRED`：这个 deviceId 已被别的账号占用，
 *   必须换一个 deviceId 与身份密钥，不能重试（§5.1 的重要行为）。
 */
export async function registerDevice(
    serverUrl: string,
    accountToken: string,
    device: DeviceDescriptor,
): Promise<DeviceCredentials> {
    const body = await requestJson<Record<string, unknown>>(serverUrl, {
        method: 'POST',
        path: '/api/v1/devices/register',
        bearer: accountToken,
        body: {
            v: 1,
            device: {
                deviceId: device.deviceId,
                name: device.name,
                role: DEVICE_ROLE,
                platform: device.platform,
                identityKey: device.identityKey,
                clientVersion: device.clientVersion,
            },
        },
    });
    return readCredentials(body);
}

/**
 * 刷新设备 token（§6）。
 *
 * Server 每次刷新都会轮换 refresh token，且禁止并发刷新（会互相作废）。
 * 调用方必须保证单飞：本函数不做互斥，因为它不知道调用方是否还有别的刷新在飞。
 */
export async function refreshDevice(
    serverUrl: string,
    deviceId: string,
    refreshToken: string,
): Promise<DeviceCredentials> {
    const body = await requestJson<Record<string, unknown>>(serverUrl, {
        method: 'POST',
        path: '/api/v1/auth/refresh',
        body: { deviceId, refreshToken },
    });
    return readCredentials(body);
}

/** access token 是否已进入刷新窗口（默认提前 60 秒，§6 建议） */
export function needsRefresh(expiresAt: number, now = Date.now(), skewMs = 60_000): boolean {
    // 服务端没给过期时间时按「需要刷新」处理：让它走一次刷新，
    // 而不是拿着一个可能已经过期的 token 去握手，失败后还要多一轮重连
    if (!Number.isFinite(expiresAt) || expiresAt <= 0) return true;
    return expiresAt - now <= skewMs;
}

// ---------------------------------------------------------------------------
// 主机发现
// ---------------------------------------------------------------------------

/**
 * 同一账号下可见的 Host。
 *
 * 只保留界面需要的字段：`deviceId` 是后续 `connect.request` 的目标，
 * `online` 决定「可连接」状态。服务端不下发信任标记（信任是本地决定），
 * 因此这里没有 `trusted`。
 */
export interface HostDevice {
    deviceId: string;
    name: string;
    platform: string;
    online: boolean;
    /**
     * 身份公钥（base64url）。Noise 握手要用它做远端静态密钥，
     * 缺了就只能连上却解不开任何消息，因此当作必需字段。
     */
    identityKey: string;
    clientVersion?: string;
    harnessVersion?: string;
    lastSeenAt?: number;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 解析 `/api/v1/devices` 的响应。
 *
 * 该接口同时返回 client 与 host，这里只留 `role === 'host'`——
 * 把手机自己列成可选主机，用户点了只会得到「目标不是 Host」。
 * 逐条容错与 `session/list` 一致：坏条目丢弃，不让整页白屏。
 */
export function readHostDevices(body: unknown): HostDevice[] {
    if (!isObject(body) || !Array.isArray(body.items)) return [];
    const hosts: HostDevice[] = [];
    for (const item of body.items) {
        const host = readHostDevice(item);
        if (host !== undefined) hosts.push(host);
    }
    return hosts;
}

function readHostDevice(item: unknown): HostDevice | undefined {
    if (!isObject(item) || item.role !== 'host') return undefined;
    const { deviceId, name, platform, membershipId, identityKey } = item;
    if (typeof deviceId !== 'string' || deviceId.length === 0) return undefined;
    if (typeof name !== 'string' || name.length === 0) return undefined;
    if (typeof platform !== 'string') return undefined;
    if (typeof membershipId !== 'string' || membershipId.length === 0) return undefined;
    // 没有身份公钥就没法做 Noise 握手，这条主机直接丢掉：
    // 留在列表里用户点了只会得到一个「解密失败」，不如不显示
    if (typeof identityKey !== 'string' || identityKey.length === 0) return undefined;

    const host: HostDevice = { deviceId, name, platform, online: item.online === true, identityKey };
    if (typeof item.clientVersion === 'string' && item.clientVersion.length > 0) {
        host.clientVersion = item.clientVersion;
    }
    if (typeof item.harnessVersion === 'string' && item.harnessVersion.length > 0) {
        host.harnessVersion = item.harnessVersion;
    }
    if (typeof item.lastSeenAt === 'number' && Number.isFinite(item.lastSeenAt)) {
        host.lastSeenAt = item.lastSeenAt;
    }
    return host;
}

/**
 * 拉取同账号可见的 Host 列表。
 *
 * 用设备 access token 而不是 account token：`/api/v1/devices` 属于设备态接口，
 * account token 只用于登录与注册。
 */
export async function listHostDevices(serverUrl: string, accessToken: string): Promise<HostDevice[]> {
    if (typeof accessToken !== 'string' || accessToken.length === 0) {
        throw new ApiError(0, 'AUTH_REQUIRED', '缺少设备 access token');
    }
    const body = await requestJson<Record<string, unknown>>(serverUrl, {
        method: 'GET',
        path: '/api/v1/devices',
        bearer: accessToken,
    });
    return readHostDevices(body);
}

// ---------------------------------------------------------------------------
// 凭证存储
// ---------------------------------------------------------------------------

/**
 * 本地保存的接入状态。
 *
 * 私钥单独放另一个槽位：`identityPrivateKey` 一旦泄露等于身份被冒充，
 * 而这份结构会随诊断信息导出，分开存能少一份误传风险。
 */
export interface StoredAuth {
    serverUrl: string;
    account: string;
    deviceId: string;
    deviceName: string;
    identityPublicKey: string;
}

const SLOT_AUTH = 'remote-auth';
const SLOT_DEVICE_TOKENS = 'remote-device-tokens';
const SLOT_IDENTITY = 'remote-identity';

export function loadAuth(): StoredAuth | null {
    return readSlot<StoredAuth>(SLOT_AUTH);
}

export function saveAuth(auth: StoredAuth): void {
    ctx.credentials.set(SLOT_AUTH, JSON.stringify(auth));
}

export function loadDeviceCredentials(): DeviceCredentials | null {
    return readSlot<DeviceCredentials>(SLOT_DEVICE_TOKENS);
}

export function saveDeviceCredentials(credentials: DeviceCredentials): void {
    ctx.credentials.set(SLOT_DEVICE_TOKENS, JSON.stringify(credentials));
}

/** 身份密钥对。私钥只在本机，不上传、不写日志 */
export function loadIdentity(): { publicKey: string; privateKey: string } | null {
    return readSlot<{ publicKey: string; privateKey: string }>(SLOT_IDENTITY);
}

export function saveIdentity(pair: { publicKey: string; privateKey: string }): void {
    ctx.credentials.set(SLOT_IDENTITY, JSON.stringify(pair));
}

export function clearAllAuth(): void {
    for (const slot of [SLOT_AUTH, SLOT_DEVICE_TOKENS, SLOT_IDENTITY]) {
        ctx.credentials.set(slot, null);
    }
}

function readSlot<T>(slot: string): T | null {
    const raw = ctx.credentials.get(slot);
    if (typeof raw !== 'string' || raw.length === 0) return null;
    try {
        return JSON.parse(raw) as T;
    } catch {
        // 存坏了就当没有：继续用一个半截 JSON 只会让后续每步都报奇怪错误
        return null;
    }
}
