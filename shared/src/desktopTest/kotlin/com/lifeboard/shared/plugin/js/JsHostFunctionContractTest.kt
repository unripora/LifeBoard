package com.lifeboard.shared.plugin.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁住 `js-plugins/types/ctx.d.ts` 与宿主实际注入的函数集一致。
 *
 * ## 为什么需要这个测试
 *
 * TS 声明是**手写的**，宿主是**Kotlin 实现的**，两者之间没有任何机制保证同步。
 * 而两边不一致的后果是静默的、且方向相反：
 *
 * - **宿主管用但声明漏了**：插件作者写 `ctx.http.xxx()` 时 TS 报「属性不存在」。
 *   他查宿主文档发现确实支持，只能加 `as any` 或关掉检查绕过——类型定义从此形同虚设。
 * - **声明有但宿主没有**：TS 编译通过、本地 Node 桩也可能通过，装到手机上
 *   调用时才炸 `is not a function`，而报错点离真正原因很远。
 *
 * 这两种都不会有编译错误、不会有测试失败，只会在几周后的某次排查里突然暴露。
 * 所以必须有测试主动比对。
 *
 * ## 断言什么
 *
 * 1. `ctx.d.ts` 里能被**调用**的成员（`name(` 形式的属性和方法）必须与
 *    [JsHostFunctions.ALL] 一一对应；
 * 2. 四个桥接的 `functionNames()` 合起来等于 [JsHostFunctions.ALL]，
 *    防止有人在桥接里独自加函数却忘了登记；
 * 3. [JsPluginApi.CAPABILITIES] 覆盖全部函数，防止加了能力却不记录版本。
 */
class JsHostFunctionContractTest {

    /**
     * 从 `ctx.d.ts` 提取声明了的 `ctx.*` 调用路径。
     *
     * ## 为什么用正则而不是引 TS 编译器
     *
     * 引入 TypeScript 编译器需要一个 Node 工具链，而这是个 JVM 单测——
     * 为了读一份自己维护的声明文件去拉起 npm，代价与收益不成比例。
     *
     * 正则在这里够用，因为声明文件的写法是受约束的：接口名固定
     * （`HttpApi` / `FilesApi` / ...），方法一律写成 `name(...)`。
     * 万一有人换成别的写法，测试会因为「少了几个函数」而失败，
     * 那正是希望被发现的情况，不会静默放过。
     */
    private fun declaredInDts(): Set<String> {
        val file = resolveTypesFile()
        val text = file.readText()

        // 接口名 → ctx 上的属性名
        val interfaceToPath = mapOf(
            "CredentialsApi" to "ctx.credentials",
            "FilesApi" to "ctx.files",
            "HttpApi" to "ctx.http",
            "CryptoApi" to "ctx.crypto",
            "CodecApi" to "ctx.codec",
            "LogApi" to "ctx.log",
            "WsApi" to "ctx.ws",
            "StreamApi" to "ctx.stream",
            "SessionApi" to "ctx.session",
        )

        val found = mutableSetOf<String>()

        // ① 从各 Api 接口里抓方法：identifier(
        interfaceToPath.forEach { (iface, path) ->
            val body = interfaceBody(text, iface) ?: error("ctx.d.ts 里找不到 interface $iface")
            // 去掉注释，避免注释里的括号被当成方法
            val cleaned = body
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                .replace(Regex("//[^\n]*"), "")
            Regex("""(?m)^\s{1,8}(\w+)\s*(?:<[^>]*>)?\s*\(""").findAll(cleaned).forEach {
                found += "$path.${it.groupValues[1]}"
            }
        }

        // ② PluginCtx 上直接声明的方法（recentMetrics 等）与属性
        val ctxBody = interfaceBody(text, "PluginCtx")
            ?: error("ctx.d.ts 里找不到 interface PluginCtx")
        val cleaned = ctxBody
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")

        Regex("""(?m)^\s{1,8}(\w+)\s*(?:<[^>]*>)?\s*\(""").findAll(cleaned).forEach {
            found += "ctx.${it.groupValues[1]}"
        }
        // 属性式能力（credentials / files / http / crypto / codec / ws / log）不进比对：
        // 它们是对象不是函数，函数由上面的接口展开覆盖
        return found
    }

    /** 取出 `interface Xxx { ... }` 的大括号内容（按层配对，避免被嵌套对象截断）。 */
    private fun interfaceBody(text: String, name: String): String? {
        val start = Regex("interface\\s+$name\\s*\\{").find(text) ?: return null
        val open = text.indexOf('{', start.range.first)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /**
     * 定位 `ctx.d.ts`。
     *
     * 测试的工作目录可能是仓库根，也可能是模块目录，逐个候选试一遍；
     * 都找不到就明确失败，而不是静默跳过——「找不到就跳过」等于这条防线不存在。
     */
    private fun resolveTypesFile(): File {
        val candidates = listOf(
            "js-plugins/types/ctx.d.ts",
            "../js-plugins/types/ctx.d.ts",
            "../../js-plugins/types/ctx.d.ts",
        )
        candidates.forEach { path ->
            val f = File(path)
            if (f.exists()) return f
        }
        // 从当前目录往上找，最多 5 层
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val f = dir?.resolve("js-plugins/types/ctx.d.ts")
            if (f != null && f.exists()) return f
            dir = dir?.parentFile
        }
        error(
            "找不到 js-plugins/types/ctx.d.ts。" +
                "这条测试依赖它来锁住类型声明与宿主实现的一致性，不能跳过。" +
                "当前工作目录=${System.getProperty("user.dir")}"
        )
    }

    @Test
    fun `ctx_d_ts 声明与宿主注入的函数集一致`() {
        val declared = declaredInDts()
        val actual = JsHostFunctions.ALL.toSet()

        val missingInDts = actual - declared
        val declaredButAbsent = declared - actual

        assertTrue(
            "宿主提供了但 ctx.d.ts 没声明（插件作者会误以为不支持）：\n" +
                missingInDts.sorted().joinToString("\n") { "  $it（${JsHostFunctions.groupOf(it)}）" },
            missingInDts.isEmpty(),
        )
        assertTrue(
            "ctx.d.ts 声明了但宿主没有（编译通过、真机崩）：\n" +
                declaredButAbsent.sorted().joinToString("\n") { "  $it（${JsHostFunctions.groupOf(it)}）" },
            declaredButAbsent.isEmpty(),
        )
    }

    @Test
    fun `四个桥接注册的函数合起来就是全集`() {
        val grouped = JsHostFunctions.CONTEXT + JsHostFunctions.HTTP +
            JsHostFunctions.CRYPTO + JsHostFunctions.WS + JsHostFunctions.STREAM +
            JsHostFunctions.SESSION

        assertEquals(
            "分组清单与 ALL 不一致",
            JsHostFunctions.ALL,
            grouped,
        )
        // 重复注册会让后注册的覆盖先注册的：同一个名字出现在两个组里，
        // 行为取决于注册顺序，这种 bug 极难排查
        assertEquals(
            "函数名在不同分组里重复",
            grouped.size,
            grouped.toSet().size,
        )
    }

    @Test
    fun `每个函数都在 CAPABILITIES 里有归属版本`() {
        // CAPABILITIES 的记法是「共同前缀 + 斜杠分隔的名字」，
        // 展开逻辑见下方注释；非 ctx.* 的条目（options.timeoutMs / options.retry）
        // 是请求选项不是函数，不在比对范围内。
        val expanded = mutableSetOf<String>()
        JsPluginApi.CAPABILITIES.values.flatten().forEach { entry ->
            if (!entry.startsWith("ctx.")) return@forEach
            // 记法是「第一个完整名字 + 斜杠分隔的后续名字」：
            //   "ctx.http.get/post/request"        → ctx.http.get, ctx.http.post, ctx.http.request
            //   "ctx.http.session/setHeader/..."   → ctx.http.session, ctx.http.setHeader, ...
            // 所以按斜杠切开后，第 0 段是完整名字，其余段接在「第 0 段的父路径」后面。
            val segments = entry.split("/").map { it.trim() }.filter { it.isNotBlank() }
            expanded += segments[0]
            if (segments.size > 1) {
                val parent = segments[0].substringBeforeLast('.', missingDelimiterValue = "")
                segments.drop(1).forEach { expanded += "$parent.$it" }
            }
        }

        val undocumented = JsHostFunctions.ALL.toSet() - expanded
        assertTrue(
            "这些函数没有出现在 JsPluginApi.CAPABILITIES 里，插件作者无从知道它们属于哪一版：\n" +
                undocumented.sorted().joinToString("\n") { "  $it" } +
                "\n（已展开记录 ${expanded.size} 项；新增能力请在 CAPABILITIES 补一行）",
            undocumented.isEmpty(),
        )
    }
}
