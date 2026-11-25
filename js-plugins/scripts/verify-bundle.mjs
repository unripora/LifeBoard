// 校验 esbuild 产物是否满足 QuickJS 宿主的加载前提。
//
// 宿主 JsPluginLoader 只做两件事：
// 1. 把包根目录下所有 .js 文件按 ES Module 注册（相对路径解析，没有 node_modules）；
// 2. 调用入口模块导出的函数。
//
// 所以产物必须满足三条硬约束，任何一条不满足都会在真机上炸：
// - 是单个文件、没有裸模块 import（`import x from 'lodash'`）；
// - 不含 Node / 浏览器专属 API（URL、Buffer、TextEncoder 等）；
// - 能被 _debug 的 Node 桩加载并调用通。
//
// 用法：node scripts/verify-bundle.mjs <产物路径>

import { readFileSync } from 'node:fs';
import { resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';

const path = process.argv[2];
if (!path) {
    console.error('用法：node scripts/verify-bundle.mjs <产物路径>');
    process.exit(1);
}

const file = resolve(path);
const code = readFileSync(file, 'utf8');
const problems = [];

// ---- 1. 不允许裸模块 import ----
// 允许的只有相对路径（./ 或 ../）。宿主没有 node_modules，裸名一律解析失败。
const importRe = /^\s*import\s[^'"]*['"]([^'"]+)['"]/gm;
for (const m of code.matchAll(importRe)) {
    const spec = m[1];
    if (!spec.startsWith('./') && !spec.startsWith('../')) {
        problems.push(`裸模块 import：'${spec}'（宿主没有 node_modules）`);
    }
}

// ---- 2. 不允许 QuickJS 缺失的运行时能力 ----
// 用带边界的正则避免误报：`new URL(` 真用，`ctx.http.session` 里的 URL 字样不算。
const forbidden = [
    [/\bnew\s+URL\s*\(/, 'new URL()'],
    [/\bnew\s+URLSearchParams\s*\(/, 'new URLSearchParams()'],
    [/\bnew\s+TextEncoder\s*\(/, 'TextEncoder'],
    [/\bnew\s+TextDecoder\s*\(/, 'TextDecoder'],
    [/(?<![.\w])Buffer\s*[.(]/, 'Buffer'],
    [/(?<![.\w])require\s*\(/, 'require()'],
    [/(?<![.\w])process\s*\./, 'process'],
    [/(?<![.\w])globalThis\s*\.\s*(fetch|URL|Buffer)/, 'globalThis 上的 Node/浏览器全局'],
];
for (const [re, label] of forbidden) {
    if (re.test(code)) problems.push(`用了 QuickJS 不存在的 ${label}`);
}

if (problems.length) {
    console.error(`产物不合规：${basename(file)}`);
    problems.forEach((p) => console.error('  - ' + p));
    process.exit(1);
}

// ---- 3. 用 Node 桩实际加载并调用 ----
// 桩与宿主的行为差异有限，这一步只证明「模块能解析、导出函数能跑」，
// 不代表真机功能验证通过。
//
// 路径相对本脚本而不是产物：产物可能在 smoke/dist/ 或 mihealth/ 下，
// 按产物位置推会跳错目录。
const scriptDir = dirname(fileURLToPath(import.meta.url));
const ctxModule = await import(
    pathToFileURL(resolve(scriptDir, '..', '_debug', 'node-ctx.mjs')).href
);
ctxModule.installContext({ credentials: '' });

const mod = await import(pathToFileURL(file).href);

const required = ['fetch'];
for (const fn of required) {
    if (typeof mod[fn] !== 'function') {
        console.error(`入口缺少必需的导出函数：${fn}`);
        process.exit(1);
    }
}

const result = await mod.fetch('2026-11-17');
if (!Array.isArray(result)) {
    const ok = typeof result === 'object' && result !== null && 'status' in result;
    if (!ok) {
        console.error('fetch 返回值既不是裸数组也不是带 status 的对象');
        process.exit(1);
    }
}

// detailUi 现在返回节点树：单个节点对象，或（便利写法）节点数组。
// 两种都会被宿主接受，但绝不能是别的东西——返回 undefined 会让详情页空白。
const ui = typeof mod.detailUi === 'function' ? await mod.detailUi() : null;
if (ui !== null && typeof ui !== 'object') {
    console.error('detailUi 必须返回节点树（对象）或节点数组');
    process.exit(1);
}

/** 递归收集树里出现的 binding 引用：打包时把插件绑了什么打出来，便于自查 */
const bindings = new Set();
const walk = (node) => {
    if (Array.isArray(node)) {
        node.forEach(walk);
        return;
    }
    if (node === null || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
        if (key === 'binding' && typeof value === 'string') bindings.add(value);
        else walk(value);
    }
};
walk(ui);

console.log(`产物合规：${basename(file)}`);
console.log(`  导出：${Object.keys(mod).join(', ')}`);
console.log(`  fetch 返回：${Array.isArray(result) ? `裸数组(${result.length})` : `status=${result.status}`}`);
console.log(`  detailUi 根：${Array.isArray(ui) ? `数组(${ui.length} 项)` : (ui === null ? '无' : `节点 ${ui.type}`)}`);
if (bindings.size > 0) {
    console.log(`  detailUi 绑定：${[...bindings].join(', ')}`);
}
