package com.lifeboard.server.plugin.js

import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.Tone
import com.lifeboard.shared.plugin.js.JsTypeConverter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 侧 `detailUi` 产物到 [com.lifeboard.plugin.api.UiBlock] 的转换。
 *
 * ## 为什么单独测这一层
 *
 * `main.js` 用一批字面量 JSON 描述页面，字段名写错时 JS 不会报错
 * （JS 对象多一个少一个键都合法），宿主这边也只是安静地少渲染一块。
 * 结果是「详情页某一节莫名不见了」，排查起来毫无线索。
 * 所以把插件真实产出的结构固化在这里，字段改名会立刻失败。
 *
 * 这些 JSON 片段抄自 `js-plugins/mihealth/main.js` 的 `detailUi`，
 * 改动插件输出时应同步更新本文件。
 */
class JsTypeConverterUiTest {

    private fun blocks(vararg items: JSONObject): String = JSONArray().apply {
        items.forEach { put(it) }
    }.toString()

    @Test
    fun `text block keeps title body and tone`() {
        val json = blocks(
            JSONObject().apply {
                put("type", "text")
                put("title", "统计口径")
                put("body", "按自然日累计睡眠时长")
            }
        )
        val out = JsTypeConverter.toUiBlocks(json)
        assertEquals(1, out.size)
        val block = out[0] as TextBlock
        assertEquals("统计口径", block.title)
        assertEquals("按自然日累计睡眠时长", block.body)
        assertEquals(Tone.NEUTRAL, block.tone)
    }

    @Test
    fun `status card accepts explicit tone and null detail`() {
        val json = blocks(
            JSONObject().apply {
                put("type", "statusCard")
                put("label", "登录状态")
                put("value", "已登录")
                put("tone", "OK")
                put("detail", "demo@example.com")
            },
            JSONObject().apply {
                put("type", "statusCard")
                put("label", "登录状态")
                put("value", "未登录")
                put("tone", "ERROR")
                // 插件在 detail 为空时显式传 null，不能变成字符串 "null"
                put("detail", JSONObject.NULL)
            },
        )
        val out = JsTypeConverter.toUiBlocks(json)
        assertEquals(2, out.size)
        val ok = out[0] as StatusCardBlock
        assertEquals("已登录", ok.value)
        assertEquals(Tone.OK, ok.tone)
        assertEquals("demo@example.com", ok.detail)

        val bad = out[1] as StatusCardBlock
        assertEquals(Tone.ERROR, bad.tone)
        assertEquals(null, bad.detail)
    }

    @Test
    fun `button row parses enabled flag`() {
        // 未登录时插件把两个按钮都置为 disabled，这条要真的传到宿主
        val json = blocks(
            JSONObject().apply {
                put("type", "buttonRow")
                put(
                    "buttons",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("label", "立即同步")
                                put("actionId", "sync_now")
                                put("primary", true)
                                put("enabled", false)
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("label", "回补近 7 天")
                                put("actionId", "backfill_7d")
                                put("enabled", false)
                            }
                        )
                    },
                )
            }
        )
        val out = JsTypeConverter.toUiBlocks(json)
        val row = out[0] as ButtonRowBlock
        assertEquals(2, row.buttons.size)
        assertEquals("sync_now", row.buttons[0].actionId)
        assertTrue(row.buttons[0].primary)
        assertEquals(false, row.buttons[0].enabled)
        assertEquals(false, row.buttons[1].enabled)
        // 未显式给 primary 时默认 false
        assertEquals(false, row.buttons[1].primary)
    }

    @Test
    fun `button without actionId is dropped`() {
        // actionId 是回传标识，缺了点了也没反应，不如不渲染
        val json = blocks(
            JSONObject().apply {
                put("type", "buttonRow")
                put(
                    "buttons",
                    JSONArray().apply {
                        put(JSONObject().put("label", "没有 id"))
                        put(JSONObject().put("label", "正常").put("actionId", "ok"))
                    },
                )
            }
        )
        val row = JsTypeConverter.toUiBlocks(json)[0] as ButtonRowBlock
        assertEquals(1, row.buttons.size)
        assertEquals("ok", row.buttons[0].actionId)
    }

    @Test
    fun `bar chart parses highlight index and unit`() {
        val json = blocks(
            JSONObject().apply {
                put("type", "chart")
                put("title", "近 7 日步数")
                put(
                    "chart",
                    JSONObject().apply {
                        put("kind", "bar")
                        put(
                            "bars",
                            JSONArray().apply {
                                put(JSONObject().put("label", "03-01").put("value", 8000))
                                put(JSONObject().put("label", "03-02").put("value", 12000))
                            },
                        )
                        put("unit", " 步")
                        put("highlightIndex", 1)
                    },
                )
            }
        )
        val chart = (JsTypeConverter.toUiBlocks(json)[0] as ChartBlock).chart as BarChartSpec
        assertEquals(2, chart.bars.size)
        assertEquals(12000.0, chart.bars[1].value, 0.001)
        assertEquals("03-02", chart.bars[1].label)
        assertEquals(" 步", chart.unit)
        assertEquals(1, chart.highlightIndex)
    }

    @Test
    fun `line chart parses fractional values`() {
        // 睡眠从分钟换算成小时会出现小数，不能用 optInt 截断
        val json = blocks(
            JSONObject().apply {
                put("type", "chart")
                put("title", "近 3 日睡眠")
                put(
                    "chart",
                    JSONObject().apply {
                        put("kind", "line")
                        put(
                            "points",
                            JSONArray().apply {
                                put(JSONObject().put("label", "03-01").put("value", 7.5))
                                put(JSONObject().put("label", "03-02").put("value", 6.25))
                            },
                        )
                        put("unit", " 小时")
                    },
                )
            }
        )
        val chart = (JsTypeConverter.toUiBlocks(json)[0] as ChartBlock).chart as LineChartSpec
        assertEquals(7.5, chart.points[0].value, 0.001)
        assertEquals(6.25, chart.points[1].value, 0.001)
        assertEquals(" 小时", chart.unit)
    }

    @Test
    fun `unknown block type is skipped instead of failing`() {
        // 旧宿主遇到新插件的新区块时应少显示一块，而不是整页空白
        val json = blocks(
            JSONObject().put("type", "futureThing"),
            JSONObject().put("type", "text").put("title", "还能看到我"),
        )
        val out = JsTypeConverter.toUiBlocks(json)
        assertEquals(1, out.size)
        assertEquals("还能看到我", (out[0] as TextBlock).title)
    }

    @Test
    fun `chart with unknown spec kind is dropped`() {
        val json = blocks(
            JSONObject().apply {
                put("type", "chart")
                put("title", "未知图")
                put("chart", JSONObject().put("kind", "sankey"))
            },
            JSONObject().put("type", "text").put("title", "保留"),
        )
        val out = JsTypeConverter.toUiBlocks(json)
        assertEquals(1, out.size)
        assertEquals("保留", (out[0] as TextBlock).title)
    }

    @Test
    fun `broken json yields empty list`() {
        assertEquals(0, JsTypeConverter.toUiBlocks("not json").size)
        assertEquals(0, JsTypeConverter.toUiBlocks("{}").size)
        assertEquals(0, JsTypeConverter.toUiBlocks("").size)
    }

    @Test
    fun `full mihealth detailUi payload converts`() {
        // 用 main.js 在「未登录 + 无数据」下真实产出的结构，
        // 这条覆盖页面的整体骨架：两张状态卡缺失后仍有文案 + 按钮行
        val json = blocks(
            JSONObject().apply {
                put("type", "statusCard")
                put("label", "登录状态")
                put("value", "未登录")
                put("tone", "ERROR")
                put("detail", "请回插件列表页登录")
            },
            JSONObject().apply {
                put("type", "text")
                put("title", "还没有步数数据")
                put("body", "点下面的「立即同步」拉取今天的数据。")
            },
            JSONObject().apply {
                put("type", "text")
                put("title", "还没有睡眠数据")
                put("body", "戴手环睡觉后点「立即同步」，睡眠才会被同步过来。")
            },
            JSONObject().apply {
                put("type", "buttonRow")
                put(
                    "buttons",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("label", "立即同步")
                                put("actionId", "sync_now")
                                put("primary", true)
                                put("enabled", false)
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("label", "回补近 7 天")
                                put("actionId", "backfill_7d")
                                put("enabled", false)
                            }
                        )
                    },
                )
            },
        )
        val out = JsTypeConverter.toUiBlocks(json)
        assertEquals(4, out.size)
        assertNotNull(out[0] as StatusCardBlock)
        assertNotNull(out[1] as TextBlock)
        assertNotNull(out[2] as TextBlock)
        assertEquals(2, (out[3] as ButtonRowBlock).buttons.size)
    }

    @Test
    fun `action result for sync maps to triggerSync`() {
        val result = JsTypeConverter.toActionResult("""{"type":"triggerSync"}""")
        assertEquals(com.lifeboard.plugin.api.ActionResult.TriggerSync, result)
    }

    @Test
    fun `unknown action maps to noop`() {
        val result = JsTypeConverter.toActionResult("""{"type":"whatever"}""")
        assertEquals(com.lifeboard.plugin.api.ActionResult.NoOp, result)
    }
}
