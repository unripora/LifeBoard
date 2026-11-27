/**
 * MiHealth 登录中间态回归：验证「运行时重建后仍能恢复」与「取消后不复活」。
 *
 * 背景：快照的代数曾经是与内存里的 `sessionEpoch` 比较，而后者每次重建运行时
 * 都从 0 开始——于是插件被按需回收再拉起后，用户明明还在验证码阶段，却被告知
 * 「属于已作废的流程」。修法是改用独立加密槽位里的**跨运行时稳定身份**。
 *
 * 用法：node _mockhost/login.mjs [bundle 路径]
 * 不模拟真实网络与 QuickJS，通过不等于真机通过。
 */

import { createMockCtx, loadPlugin } from './index.mjs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const target = resolve(process.env.MI_BUNDLE || process.argv[2] || 'mihealth/main.js');

const failures = [];
function check(name, ok, detail) {
    console.log(`  ${ok ? '✓' : '✗'} ${name}${!ok && detail ? '\n      ' + detail : ''}`);
    if (!ok) failures.push(name);
}

/**
 * 新开一个「运行时」：重新 import 产物，模块级变量全部归零，
 * 但凭据表沿用上一次的内容——这正是宿主卸载再拉起时的真实状态。
 *
 * 用带随机 query 的 URL 导入，绕开 ESM 模块缓存，等价于 QuickJS 里新建运行时。
 */
async function bootRuntime(credentialState) {
    const mock = createMockCtx({
        pluginId: 'mihealth.regression',
        credentials: credentialState,
        // 登录接口在这个回归里不被驱动，任何请求都返回失败；
        // 我们只关心「中间态是否被恢复/作废」，不关心真实认证流程
        http: () => ({ status: 503, body: '{}' }),
    });
    const url = pathToFileURL(target).href + `?boot=${Math.random().toString(36).slice(2)}`;
    const mod = await loadPlugin(url, mock);
    return { mod, mock, credentials: () => mock.snapshot().credentials };
}

/** 造一份合法的 captcha 阶段快照（v2，带稳定代数） */
function seedSnapshot(credentials, generation) {
    credentials['pending_login_generation'] = generation;
    credentials['pending_login'] = JSON.stringify({
        v: 2,
        stage: 'captcha',
        account: '13800000000',
        passwordHash: 'A'.repeat(32),
        handshake: {},
        userId: '',
        cookies: [],
        expiresAt: Date.now() + 5 * 60_000,
        generation,
    });
}

async function main() {
    console.log('MiHealth 登录恢复回归：');

    // 场景 1：同一份凭据，在**新的运行时**里应当能恢复出验证码阶段
    const generation = 'gen-stable-1';
    const credentials = {};
    seedSnapshot(credentials, generation);

    const runtimeA = await bootRuntime({ ...credentials });
    const first = await runtimeA.mod.login({}, { intent: 'open' });
    void first;
    check(
        '重建运行时后能恢复登录中间态（代数不再被内存计数误判）',
        !runtimeA.mock.snapshot().logs.some((l) => String(l.msg).includes('属于已作废')),
        JSON.stringify(runtimeA.mock.snapshot().logs.map((l) => l.msg).slice(-4)),
    );
    check(
        '恢复后仍保留中间态槽位',
        typeof runtimeA.credentials()['pending_login'] === 'string',
    );

    // 场景 2：用户放弃后，旧快照不能再被恢复
    const runtimeB = await bootRuntime(runtimeA.credentials());
    await runtimeB.mod.login({}, { intent: 'abandon' });
    check(
        '主动放弃会清掉稳定代数与快照',
        runtimeB.credentials()['pending_login'] == null &&
            runtimeB.credentials()['pending_login_generation'] == null,
        JSON.stringify(Object.keys(runtimeB.credentials())),
    );

    // 场景 3：快照代数与当前代数不一致（旧流程）→ 必须丢弃
    const stale = {};
    seedSnapshot(stale, 'gen-old');
    stale['pending_login_generation'] = 'gen-new';
    const runtimeC = await bootRuntime({ ...stale });
    await runtimeC.mod.login({}, { intent: 'open' });
    check(
        '代数不一致的旧快照被丢弃',
        runtimeC.mock.snapshot().logs.some((l) => String(l.msg).includes('已作废')),
        JSON.stringify(runtimeC.mock.snapshot().logs.map((l) => l.msg).slice(-4)),
    );

    // 场景 4：过期快照必须丢弃，不能借 TTL 复活
    const expired = {};
    expired['pending_login_generation'] = 'gen-exp';
    expired['pending_login'] = JSON.stringify({
        v: 2,
        stage: 'captcha',
        account: '13800000000',
        passwordHash: 'A'.repeat(32),
        handshake: {},
        userId: '',
        cookies: [],
        expiresAt: Date.now() - 1_000,
        generation: 'gen-exp',
    });
    const runtimeD = await bootRuntime({ ...expired });
    await runtimeD.mod.login({}, { intent: 'open' });
    check(
        '过期快照被丢弃',
        runtimeD.mock.snapshot().logs.some((l) => String(l.msg).includes('已过期')),
    );
}


await main();
if (failures.length > 0) {
    console.error(`\n失败 ${failures.length} 项`);
    process.exit(1);
}
console.log('\n全部通过（假宿主，不等于真机）');
