// 把一个插件目录打成 .jsplugin（ZIP），manifest.json 放在包根目录。
//
// 宿主 JsPluginLoader 只把根目录下结尾为 .js 的文件注册为 ES 模块，
// 所以 esbuild 必须 --bundle 成单个 main.js：包内没有 node_modules，
// 运行时只解析包内相对路径。
//
// 用法：node scripts/package-plugin.mjs mihealth

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32 } from 'node:zlib';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const pluginDir = process.argv[2];

if (!pluginDir) {
    console.error('用法：node scripts/package-plugin.mjs <插件目录名>');
    process.exit(1);
}

const srcDir = join(root, pluginDir);
if (!existsSync(join(srcDir, 'manifest.json'))) {
    console.error(`缺少 ${join(srcDir, 'manifest.json')}，先跑 npm run build 生成产物`);
    process.exit(1);
}

/** 递归收集需要打进包的文件：manifest.json 与所有 .js */
function collect(dir, base = dir, out = []) {
    for (const name of readdirSync(dir)) {
        const full = join(dir, name);
        const rel = relative(base, full).replaceAll('\\', '/');
        if (statSync(full).isDirectory()) {
            // 源码目录与调试目录不进包
            if (rel === 'src' || rel === 'types' || rel === 'node_modules') continue;
            collect(full, base, out);
        } else if (rel === 'manifest.json' || rel.endsWith('.js')) {
            out.push({ name: rel, data: readFileSync(full) });
        }
    }
    return out;
}

// ---- 最小 ZIP 写入器（store 模式，不压缩） ----
// 不引第三方依赖：包的体积由脚本决定，用 deflate 也省不了多少，
// 但少一个 npm 包就少一处供应链风险。

function dosTime(d) {
    const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
    const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
    return { time, date };
}

function buildZip(entries) {
    const now = dosTime(new Date());
    const locals = [];
    const centrals = [];
    let offset = 0;

    for (const e of entries) {
        const nameBuf = Buffer.from(e.name, 'utf8');
        const crc = crc32(e.data) >>> 0;

        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);        // version needed
        local.writeUInt16LE(0x0800, 6);    // UTF-8 文件名
        local.writeUInt16LE(0, 8);         // store
        local.writeUInt16LE(now.time, 10);
        local.writeUInt16LE(now.date, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(e.data.length, 18);
        local.writeUInt32LE(e.data.length, 22);
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(0, 28);
        locals.push(local, nameBuf, e.data);

        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(0x0800, 8);
        central.writeUInt16LE(0, 10);
        central.writeUInt16LE(now.time, 12);
        central.writeUInt16LE(now.date, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(e.data.length, 20);
        central.writeUInt32LE(e.data.length, 24);
        central.writeUInt16LE(nameBuf.length, 28);
        central.writeUInt32LE(offset, 42);
        centrals.push(central, nameBuf);

        offset += local.length + nameBuf.length + e.data.length;
    }

    const centralBuf = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(centralBuf.length, 12);
    end.writeUInt32LE(offset, 16);

    return Buffer.concat([...locals, centralBuf, end]);
}

const entries = collect(srcDir);
const manifest = JSON.parse(readFileSync(join(srcDir, 'manifest.json'), 'utf8'));
const outPath = join(root, `${manifest.id}.jsplugin`);

writeFileSync(outPath, buildZip(entries));
console.log(`已生成 ${outPath}`);
console.log(`  文件：${entries.map((e) => e.name).join(', ')}`);
