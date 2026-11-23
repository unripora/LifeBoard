package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.json.PluginJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [JsCryptoBridge] 的测试。
 *
 * ## 为什么大部分用例直接调 `invoke`
 *
 * 这个桥没有跨线程、没有异步、没有状态，纯粹是「JSON 参数进、JSON 结果出」。
 * 拉一个 QuickJS 运行时来测只会让反馈变慢，还测不到任何新东西。
 *
 * 唯一需要真引擎的是最后一条：验证**上游 `toBase64Url` / `fromBase64Url`
 * 在注入 polyfill 后能原样运行**——那是 M2 的验收项，而「原样运行」这句话
 * 只有真的让 JS 跑起来才算数。
 *
 * ## 断言方式
 *
 * 摘要类用**公开的测试向量**（NIST 的 `abc`、SHA-256 空串），不是拿同一份
 * `MessageDigest` 自证——那种写法实现写错时测试也会跟着错。
 */
class JsCryptoBridgeTest {

    private val bridge = JsCryptoBridge()

    // ---------- 2.1 安全随机字节 ----------

    @Test
    fun `randomBytes 返回指定长度且每次不同`() {
        val first = ints(call("ctx.crypto.randomBytes", "[32]"))
        val second = ints(call("ctx.crypto.randomBytes", "[32]"))

        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertTrue(first.all { it in 0..255 }, "字节超出 0..255：$first")
        assertNotEquals(first, second, "两次随机字节完全相同，随机源可能没接上")
    }

    @Test
    fun `randomBytes 的长度越界被拒绝`() {
        assertTrue(call("ctx.crypto.randomBytes", "[0]").startsWith("<error:"))
        assertTrue(call("ctx.crypto.randomBytes", "[4097]").startsWith("<error:"))
        assertTrue(call("ctx.crypto.randomBytes", "[\"abc\"]").startsWith("<error:"))
    }

    // ---------- 2.2 字节进字节出的摘要 ----------

    @Test
    fun `sha256Bytes 与公开测试向量一致`() {
        // NIST 向量：SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(ints(call("ctx.crypto.sha256Bytes", "[[97,98,99]]"))),
        )
        // SHA-256("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(ints(call("ctx.crypto.sha256Bytes", "[[]]"))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            call("ctx.crypto.sha256Hex", "[[97,98,99]]").lowercase(),
        )
    }

    // ---------- 2.3 base64url ----------

    @Test
    fun `base64Url 编解码与 RFC 4648 向量一致且往返无损`() {
        // 标准测试向量：f / fo / foo / foob / fooba / foobar
        assertEquals("Zg", encodeUrl(102))
        assertEquals("Zm8", encodeUrl(102, 111))
        assertEquals("Zm9v", encodeUrl(102, 111, 111))
        assertEquals("Zm9vYg", encodeUrl(102, 111, 111, 98))
        assertEquals("Zm9vYmE", encodeUrl(102, 111, 111, 98, 97))
        assertEquals("Zm9vYmFy", encodeUrl(102, 111, 111, 98, 97, 114))

        // URL-safe 字母表：0xfb 0xff → "-_8"（标准 base64 是 "+/8="）
        assertEquals("-_8", encodeUrl(251, 255))
        assertEquals(listOf(251, 255), decodeUrl("-_8"))

        // 空输入往返
        assertEquals("", unquote(call("ctx.codec.base64UrlEncode", "[[]]")))
        assertTrue(decodeUrl("").isEmpty())

        // 带补齐的输入也要能解（对端实现不一定去掉 "="）
        assertEquals(listOf(102), decodeUrl("Zg=="))

        // 任意字节往返
        val sample = (0..255).toList()
        val encoded = encodeUrl(*sample.toIntArray())
        assertEquals(sample, decodeUrl(encoded))

        // 非法输入按约定返回空数组，而不是让宿主抛错
        assertTrue(decodeUrl("!!!!").isEmpty())
    }

    // ---------- 2.4 UTF-8 ----------

    @Test
    fun `utf8 编解码与宿主一致`() {
        // 中文：中 U+4E2D E4 B8 AD；文 U+6587 E6 96 87
        assertEquals(listOf(228, 184, 173, 230, 150, 135), encodeUtf8("中文"))
        assertEquals("中文", decodeUtf8(228, 184, 173, 230, 150, 135))

        // emoji（四字节序列）：😀 U+1F600 → F0 9F 98 80
        assertEquals(listOf(240, 159, 152, 128), encodeUtf8("😀"))
        assertEquals("😀", decodeUtf8(240, 159, 152, 128))

        // 空串合法
        assertTrue(encodeUtf8("").isEmpty())
        assertEquals("", decodeUtf8())

        // ASCII 与多字节混排往返
        val text = "a中😀b"
        assertEquals(text, unquote(call("ctx.codec.utf8Decode", "[${call("ctx.codec.utf8Encode", "[\"$text\"]")}]")))

        // 非法字节解成替换符，不抛错（与 TextDecoder 默认行为一致）
        assertEquals("\uFFFD", decodeUtf8(255))
    }

    // ---------- 2.5 上游 polyfill 可原样运行 ----------

    /**
     * 上游 `crypto/src/index.ts` 只用 `btoa` / `atob` / `TextEncoder` / `TextDecoder`
     * 这些标准全局对象。QuickJS 没有它们，但宿主补齐字节级能力后，
     * 用一层十来行的 polyfill 就能让上游代码**一字不改**地跑起来。
     *
     * 这条用例把 polyfill 与上游写法都放进 JS，走真实引擎执行：
     * 若某个宿主函数的返回形态（字符串 / 字节数组）不对，这里会直接炸。
     */
    @Test
    fun `上游 toBase64Url 与 TextEncoder 在注入 polyfill 后可原样运行`() {
        val runtime = JsPluginRuntime.create("test-crypto-polyfill")
        try {
            val module = """
                // ---- 注入 QuickJS 缺失的全局对象（与上游业务代码无关） ----
                globalThis.btoa = (binary) => {
                  const bytes = [];
                  for (let i = 0; i < binary.length; i++) bytes.push(binary.charCodeAt(i) & 0xff);
                  return ctx.codec.base64Encode(bytes);
                };
                globalThis.atob = (text) => {
                  const bytes = ctx.codec.base64Decode(text);
                  let out = '';
                  for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                  return out;
                };
                globalThis.TextEncoder = class {
                  encode(text) { return ctx.codec.utf8Encode(text); }
                };
                globalThis.TextDecoder = class {
                  decode(bytes) { return ctx.codec.utf8Decode(bytes); }
                };

                // ---- 以下与上游 crypto/src/index.ts 的写法一致 ----
                export function toBase64Url(bytes) {
                  let binary = '';
                  for (const b of bytes) binary += String.fromCharCode(b);
                  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
                }

                export function fromBase64Url(text) {
                  const base64 = text.replace(/-/g, '+').replace(/_/g, '/');
                  const pad = (4 - (base64.length % 4)) % 4;
                  const binary = atob(base64 + '='.repeat(pad));
                  const out = [];
                  for (let i = 0; i < binary.length; i++) out.push(binary.charCodeAt(i) & 0xff);
                  return out;
                }
            """.trimIndent()

            val started = runtime.start(
                modules = mapOf("main.js" to module),
                entry = "main.js",
            ) { CompositeHostDispatcher(listOf(bridge.functionNames() to bridge)) }
            assertTrue(started.isSuccess, "运行时启动失败：${started.exceptionOrNull()}")

            val results = runtime.runOnRuntime { rt ->
                // 注意：宿主函数按名注册到全局 ctx，模块顶层就能引用
                listOf(
                    rt.callFunction("main.js", "toBase64Url", "[[251,255]]"),
                    rt.callFunction("main.js", "toBase64Url", "[[102,111,111]]"),
                    rt.callFunction("main.js", "fromBase64Url", "[\"-_8\"]"),
                    rt.callFunction("main.js", "fromBase64Url", "[\"Zm9v\"]"),
                )
            }

            assertEquals("-_8", unquote(results[0]))
            assertEquals("Zm9v", unquote(results[1]))
            assertEquals(listOf(251, 255), ints(results[2]))
            assertEquals(listOf(102, 111, 111), ints(results[3]))
        } finally {
            runtime.close()
        }
    }

    // ---------- 辅助 ----------

    private fun call(name: String, argsJson: String): String = bridge.invoke(name, argsJson)

    private fun encodeUrl(vararg bytes: Int): String =
        unquote(call("ctx.codec.base64UrlEncode", "[[${bytes.joinToString(",")}]]"))

    private fun decodeUrl(text: String): List<Int> =
        ints(call("ctx.codec.base64UrlDecode", "[\"$text\"]"))

    private fun encodeUtf8(text: String): List<Int> =
        ints(call("ctx.codec.utf8Encode", "[\"$text\"]"))

    private fun decodeUtf8(vararg bytes: Int): String =
        unquote(call("ctx.codec.utf8Decode", "[[${bytes.joinToString(",")}]]"))

    /** 结果形如 `[1,2,3]` 时解析成整数列表。 */
    private fun ints(json: String): List<Int> {
        val arr = PluginJsonArray(json)
        return (0 until arr.length()).map { arr.optInt(it, -1) }
    }

    /**
     * 去掉 JSON 字符串的引号。
     *
     * 桥的返回值有两种形态：JSON 字符串（`"abc"`）与裸文本（十六进制、base64）。
     * 裸文本在 JS 侧解析失败后会原样返回，是既有约定，这里跟着兼容。
     */
    private fun unquote(json: String): String =
        if (json.length >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
            json.substring(1, json.length - 1)
        } else {
            json
        }

    private fun hex(bytes: List<Int>): String =
        bytes.joinToString("") { "%02x".format(it) }
}