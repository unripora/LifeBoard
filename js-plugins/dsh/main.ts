// DSH 插件入口。
//
// ## 这个文件只做两件事
//
// 1. 把契约出口再导出一次（宿主只认入口模块 `main.js` 的具名导出）
// 2. 提供离线自检，让宿主测试能在没有网关的环境里验证协议层
//
// 业务实现分别在 `contract.ts`（契约）/ `gateway.ts`（配对与鉴权）/
// `sessionWire.ts`（会话调用）/ `mux.ts`（逻辑流）/ `rpc.ts`（一元调用）。
//
// ## 为什么自检要放在入口
//
// 宿主测试（`DshPluginBundleTest`）通过真 QuickJS 调用入口的导出函数。
// 把自检放这里，就不必为测试额外暴露内部模块，也不必让它们进公开 API。

/// <reference path="../types/index.d.ts" />

import {
    authState,
    detailUi,
    fetch as fetchMetrics,
    health,
    login,
    logout,
    onAction,
    sessionEvent,
    sessionStart,
    sessionStop,
} from './contract.js';
import { hostOf, normalizeGatewayUrl, parsePairingLink } from './gateway.js';
import { parseServerFrame } from './mux.js';

// 契约出口：宿主只认入口模块（`main.js`）的具名导出，因此这里必须再导出一次
export {
    authState,
    detailUi,
    health,
    login,
    logout,
    onAction,
    sessionEvent,
    sessionStart,
    sessionStop,
};
export { fetchMetrics as fetch };

/** 自检结果：逐条通过/失败，便于定位具体哪一项不对 */
export interface SelfTestResult {
    ok: boolean;
    checks: string[];
    failures: string[];
}

/**
 * 网关地址规范化的自检。
 *
 * 地址错一个字符就会连到错误的位置，而错误信息通常只有一个 403/404，
 * 所以边界（末尾斜杠、带路径、http、带凭据）必须逐条验证。
 */
export function gatewaySelfTest(): SelfTestResult {
    const checks: string[] = [];
    const failures: string[] = [];

    const expectOk = (input: string, expected: string): void => {
        try {
            const actual = normalizeGatewayUrl(input);
            if (actual === expected) checks.push(`${input} → ${expected}`);
            else failures.push(`${input} 应为 ${expected}，实际 ${actual}`);
        } catch (e) {
            failures.push(`${input} 本应接受，却抛错：${String(e)}`);
        }
    };

    const expectFail = (input: string, why: string): void => {
        try {
            const actual = normalizeGatewayUrl(input);
            failures.push(`${input} 本应拒绝（${why}），却返回 ${actual}`);
        } catch {
            checks.push(`${input} 被拒绝（${why}）`);
        }
    };

    expectOk('https://192.168.1.10:3443', 'https://192.168.1.10:3443');
    expectOk('  https://192.168.1.10:3443/  ', 'https://192.168.1.10:3443');
    expectOk('HTTPS://host.local:3443', 'https://host.local:3443');
    expectFail('http://192.168.1.10:3443', '必须是 https');
    expectFail('https://192.168.1.10:3443/api', '不能带路径');
    expectFail('https://user:pass@host:3443', '不能带凭据');
    expectFail('', '不能为空');
    expectFail('192.168.1.10:3443', '缺 scheme');

    // hostOf 必须与规范化结果一致，否则 Cookie 的 domain 会与请求 host 对不上
    try {
        const host = hostOf('https://192.168.1.10:3443');
        if (host === '192.168.1.10:3443') checks.push('hostOf 拆出 host:port');
        else failures.push(`hostOf 结果异常：${host}`);
    } catch (e) {
        failures.push(`hostOf 抛错：${String(e)}`);
    }

    // 配对链接解析：CA 指纹（instance）是分发给多用户时唯一的钉扎来源，
    // 取错就等于钉错了证书。三种粘贴形态里只有「整条链接」是全的。
    const sampleInstance = 'a'.repeat(64);
    const sampleToken = 'b'.repeat(43);
    const full = parsePairingLink(
        `https://192.168.1.10:3443/mobile-access/pair#instance=${sampleInstance}&token=${sampleToken}`,
    );
    if (full === null) failures.push('整条配对链接本应解析成功');
    else if (full.gateway !== 'https://192.168.1.10:3443' || full.instance !== sampleInstance || full.token !== sampleToken) {
        failures.push(`配对链接解析结果异常：${JSON.stringify(full)}`);
    } else checks.push('整条配对链接解析');

    // `?` 查询写法也要认，有些客户端会把参数放在 search 里
    const viaQuery = parsePairingLink(`https://host:3443/pair?instance=${sampleInstance}&token=${sampleToken}`);
    if (viaQuery !== null && viaQuery.instance === sampleInstance) checks.push('? 形式配对链接解析');
    else failures.push('? 形式的配对链接未能解析');

    // 以下都必须拒绝：只粘裸码没有指纹，钉扎无从下手
    const expectLinkRejected = (input: string, why: string): void => {
        if (parsePairingLink(input) === null) checks.push(`${why} → 拒绝`);
        else failures.push(`${why} 本应拒绝，却解析成功`);
    };
    expectLinkRejected(sampleToken, '裸配对码（无 instance）');
    expectLinkRejected(`https://h:3443/pair#token=${sampleToken}`, '链接缺 instance');
    expectLinkRejected(`https://h:3443/pair#instance=${sampleInstance}`, '链接缺 token');
    expectLinkRejected(`https://h:3443/pair#instance=abc&token=${sampleToken}`, 'instance 长度不对');
    expectLinkRejected('', '空串');

    return { ok: failures.length === 0, checks, failures };
}

/**
 * mux 帧解析的自检。
 *
 * 网关对下行帧做严格校验，我们收帧时也必须同样严格——
 * 畸形帧若被当成正常帧，症状是消息内容错乱，回溯成本极高。
 */
export function muxSelfTest(): SelfTestResult {
    const checks: string[] = [];
    const failures: string[] = [];

    const expectItem = (text: string, expectValue: unknown): void => {
        const frame = parseServerFrame(text);
        if (frame === null) {
            failures.push(`${text} 本应解析为 item，却返回 null`);
            return;
        }
        if (frame.type !== 'item') {
            failures.push(`${text} 应为 item，实际 ${frame.type}`);
            return;
        }
        const actual = JSON.stringify(frame.value);
        const expected = JSON.stringify(expectValue);
        if (actual === expected) checks.push(`${text} → item`);
        else failures.push(`${text} value 应为 ${expected}，实际 ${actual}`);
    };

    const expectNull = (text: string, why: string): void => {
        const frame = parseServerFrame(text);
        if (frame === null) checks.push(`${text} 被拒绝（${why}）`);
        else failures.push(`${text} 本应拒绝（${why}），却解析为 ${frame.type}`);
    };

    expectItem('{"type":"item","streamId":"s1","value":{"a":1}}', { a: 1 });
    expectItem('{"type":"item","streamId":"s1"}', undefined);
    // value 显式为 null 是合法的（网关自己也这么发）
    expectItem('{"type":"item","streamId":"s1","value":null}', null);

    const end = parseServerFrame('{"type":"end","streamId":"s1"}');
    if (end !== null && end.type === 'end') checks.push('end 帧解析');
    else failures.push('end 帧解析失败');

    const err = parseServerFrame('{"type":"error","streamId":"s1","error":{"code":"c","message":"m","details":{}}}');
    if (err !== null && err.type === 'error' && err.error.code === 'c') checks.push('error 帧解析');
    else failures.push('error 帧解析失败');

    expectNull('not json', '不是 JSON');
    expectNull('[]', '不是对象');
    expectNull('{"type":"item"}', '缺 streamId');
    expectNull('{"type":"item","streamId":""}', 'streamId 为空');
    expectNull('{"type":"item","streamId":"s1","extra":1}', '多余字段');
    expectNull('{"type":"end","streamId":"s1","x":1}', 'end 多余字段');
    expectNull('{"type":"error","streamId":"s1","error":{"code":"c"}}', 'error 缺 message');
    expectNull('{"type":"unknown","streamId":"s1"}', '未知 type');

    return { ok: failures.length === 0, checks, failures };
}

/**
 * 契约出口的自检。
 *
 * 验证宿主调用的每个导出都存在。少一个导出的表现是
 * `undefined is not a function`，报错点离真正原因很远。
 */
export function contractSelfTest(): SelfTestResult {
    const checks: string[] = [];
    const failures: string[] = [];

    const actual = Object.keys(contractExports());
    for (const name of CONTRACT_EXPORTS) {
        if (actual.includes(name)) checks.push(`导出 ${name}`);
        else failures.push(`缺少导出 ${name}`);
    }
    return { ok: failures.length === 0, checks, failures };
}

/** 宿主依赖的契约出口清单（`SourcePlugin` + 详情页 + 会话四函数） */
const CONTRACT_EXPORTS = [
    'authState', 'detailUi', 'fetch', 'health', 'login',
    'logout', 'onAction', 'sessionEvent', 'sessionStart', 'sessionStop',
] as const;

function contractExports(): Record<string, unknown> {
    return {
        authState,
        detailUi,
        fetch: fetchMetrics,
        health,
        login,
        logout,
        onAction,
        sessionEvent,
        sessionStart,
        sessionStop,
    };
}
