/**
 * 假宿主冒烟：加载一个已打包的插件产物，调几个纯离线方法，打印结果。
 *
 * 只验证「模块能载入 + 返回结构像样」。**不**验证网络、限额、并发。
 *
 * 用法：node _mockhost/smoke.mjs <插件 main.js 路径>
 */

import { createMockCtx, loadPlugin } from './index.mjs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const target = process.argv[2];
if (!target) {
    console.error('用法：node _mockhost/smoke.mjs <插件 main.js 路径>');
    process.exit(2);
}

const mock = createMockCtx({ pluginId: 'smoke.plugin' });
const mod = await loadPlugin(pathToFileURL(resolve(target)).href, mock);

const exportsFound = Object.keys(mod).filter((k) => typeof mod[k] === 'function').sort();
console.log('导出函数：', exportsFound.join(', ') || '(无)');

const required = ['login', 'logout', 'authState', 'fetch', 'health'];
const missing = required.filter((name) => typeof mod[name] !== 'function');
if (missing.length > 0) {
    console.error('缺少必需导出：', missing.join(', '));
    process.exit(1);
}

const auth = await mod.authState();
console.log('authState：', JSON.stringify(auth));

const ui = typeof mod.detailUi === 'function' ? await mod.detailUi() : undefined;
console.log('detailUi 根：', ui == null ? 'null' : Array.isArray(ui) ? `数组(${ui.length} 项)` : ui.type);

const result = await mod.fetch('2030-01-01');
const status = Array.isArray(result) ? '裸数组(成功)' : result && result.status;
console.log('fetch 返回：status=' + status);

if (status == null) {
    console.error('fetch 返回结构不符合契约');
    process.exit(1);
}

console.log('日志条数：', mock.snapshot().logs.length);
console.log('冒烟通过（注意：这不等于真机通过）');
