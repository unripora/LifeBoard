package com.lifeboard.server.plugin.js

import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.ChartNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.shared.plugin.js.JsTypeConverter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 侧 `detailUi` 产物到 [com.lifeboard.plugin.api.PluginNode] 节点树的转换。
 *
 * ## 为什么单独测这一层
 *
 * `main.js` 用一批字面量 JSON 描述页面，字段名写错时 JS 不会报错
 * （JS 对象多一个少一个键都合法），宿主这边也只是安静地少渲染一块。
 * 结果是「详情页某一节莫名不见了」，排查起来毫无线索。
 * 所以把插件真实产出的结构固化在这里，字段改名会立刻失败。
 *
 * 这些 JSON 片段抄自 `js-plugins/mihealth/main.js` 与 `js-plugins/dsh/contract.ts`
 * 的 `detailUi`，改动插件输出时应同步更新本文件。
 */
class JsTypeConverterUiTest {

    private fun nodes(vararg items: JSONObject): String = JSONArray().apply {
        items.forEach { put(it) }
    }.toString()

    /** 数组根会被包成一层不套卡片的 ColumnNode */
    private fun rootOf(json: String) = JsTypeConverter.toUiTree(json).root

    @Test
    fun `text node keeps title body and tone`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "text")
                put("title", "统计口径")
                put("body", "按自然日累计睡眠时长")
            }
        )
        val root = rootOf(json) as ColumnNode
        assertEquals(1, root.children.size)
        val node = root.children[0] as TextNode
        assertEquals("统计口径", node.title)
        assertEquals("按自然日累计睡眠时长", (node.body as Bound.Static).value)
        assertEquals(Tone.NEUTRAL, node.tone)
        // 数组根自动包的那层不套卡片：否则平铺几个区块会多出一圈边框
        assertEquals(false, root.card)
    }

    @Test
    fun `status card accepts explicit tone and detail`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "statusCard")
                put("label", "登录状态")
                put("value", "已登录")
                put("tone", "ok")
                put("detail", "上次同步 08:00")
            }
        )
        val node = (rootOf(json) as ColumnNode).children[0] as StatusCardNode
        assertEquals("登录状态", node.label)
        assertEquals("已登录", (node.value as Bound.Static).value)
        assertEquals(Tone.OK, node.tone)
        assertEquals("上次同步 08:00", node.detail)
    }

    @Test
    fun `button row becomes row of buttons`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "row")
                put("card", false)
                put(
                    "children",
                    JSONArray().apply {
                        put(JSONObject().apply { put("type", "button"); put("label", "立即同步"); put("actionId", "sync"); put("primary", true) })
                        put(JSONObject().apply { put("type", "button"); put("label", "回补"); put("actionId", "backfill") })
                    },
                )
            }
        )
        val row = (rootOf(json) as ColumnNode).children[0] as RowNode
        assertEquals(2, row.children.size)
        val primary = row.children[0] as ButtonNode
        assertEquals("立即同步", primary.label)
        assertEquals("sync", primary.actionId)
        assertTrue(primary.primary)
        assertEquals("backfill", (row.children[1] as ButtonNode).actionId)
    }

    @Test
    fun `bar chart is parsed with highlight index`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "chart")
                put("title", "近 7 日步数")
                put(
                    "chart",
                    JSONObject().apply {
                        put("kind", "bar")
                        put("unit", " 步")
                        put("highlightIndex", 6)
                        put(
                            "bars",
                            JSONArray().apply {
                                put(JSONObject().apply { put("label", "11-17"); put("value", 8000) })
                            },
                        )
                    },
                )
            }
        )
        val node = (rootOf(json) as ColumnNode).children[0] as ChartNode
        assertEquals("近 7 日步数", node.title)
        val spec = node.chart as BarChartSpec
        assertEquals(" 步", spec.unit)
        assertEquals(6, spec.highlightIndex)
        assertEquals(8000.0, spec.bars[0].value, 0.0)
    }

    @Test
    fun `line chart is parsed`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "chart")
                put("title", "睡眠")
                put(
                    "chart",
                    JSONObject().apply {
                        put("kind", "line")
                        put("points", JSONArray().apply {
                            put(JSONObject().apply { put("label", "11-17"); put("value", 7.5) })
                        })
                    },
                )
            }
        )
        val spec = ((rootOf(json) as ColumnNode).children[0] as ChartNode).chart as LineChartSpec
        assertEquals(7.5, spec.points[0].value, 0.0)
    }

    /**
     * 未知 type 必须降级为占位，而不是像旧实现那样静默丢弃。
     *
     * 静默丢弃的表现是「插件说的东西少了一块」，用户与开发者都没有线索；
     * 占位至少能让人知道「这里本来有东西，是 App 太旧」。
     */
    @Test
    fun `unknown node type degrades to placeholder`() {
        val json = nodes(JSONObject().apply { put("type", "hologram"); put("id", "x") })
        val node = (rootOf(json) as ColumnNode).children[0]
        assertTrue(node is UnsupportedNode)
        assertEquals("hologram", (node as UnsupportedNode).type)
        assertEquals("x", node.id)
    }

    /** 旧区块类型（conversation）也应降级为占位，不再是「悄悄消失」 */
    @Test
    fun `legacy block type degrades to placeholder`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "conversation")
                put("sessionId", "main")
            }
        )
        assertTrue((rootOf(json) as ColumnNode).children[0] is UnsupportedNode)
    }

    @Test
    fun `binding is parsed on text body and list items`() {
        val json = JSONObject().apply {
            put("type", "column")
            put(
                "children",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "text")
                            put("body", JSONObject().apply { put("binding", "session:main:status") })
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("type", "list")
                            put("items", JSONObject().apply { put("binding", "session:main:messages") })
                        },
                    )
                },
            )
        }.toString()

        val column = rootOf(json) as ColumnNode
        val text = column.children[0] as TextNode
        assertEquals("session:main:status", (text.body as Bound.Ref).ref)
        val list = column.children[1] as ListNode
        assertEquals("session:main:messages", (list.items as Bound.Ref).ref)
    }

    /** 畸形 binding 退回静态默认值，不能抛异常也不能让整页打不开 */
    @Test
    fun `malformed binding falls back to static`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "text")
                put("body", JSONObject().apply { put("binding", "not-a-ref") })
            }
        )
        val text = (rootOf(json) as ColumnNode).children[0] as TextNode
        assertEquals("", (text.body as Bound.Static).value)
    }

    @Test
    fun `input without actionId is dropped but button is kept`() {
        val json = nodes(
            JSONObject().apply { put("type", "input"); put("placeholder", "无 actionId") },
            JSONObject().apply { put("type", "button"); put("label", "无名按钮") },
        )
        val children = (rootOf(json) as ColumnNode).children
        // 没有 actionId 的输入框提交无门，丢弃比渲染出「点了没反应」好
        assertEquals(1, children.size)
        assertTrue(children[0] is ButtonNode)
        // 按钮保留但 actionId 为空，渲染层会画成禁用态
        assertNull((children[0] as ButtonNode).actionId)
    }

    @Test
    fun `input with submitTo keeps the binding`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "input")
                put("actionId", "dsh.send")
                put("submitTo", "session:main")
                put("enabled", JSONObject().apply { put("binding", "session:main:inputEnabled") })
            }
        )
        val input = (rootOf(json) as ColumnNode).children[0] as InputNode
        assertEquals("main", input.submitTo?.key)
        assertEquals("session", input.submitTo?.source)
        assertEquals("session:main:inputEnabled", (input.enabled as Bound.Ref).ref)
    }

    @Test
    fun `chart with unknown kind degrades instead of vanishing`() {
        val json = nodes(
            JSONObject().apply {
                put("type", "chart")
                put("chart", JSONObject().apply { put("kind", "sankey") })
            }
        )
        val node = (rootOf(json) as ColumnNode).children[0]
        assertTrue(node is UnsupportedNode)
        assertTrue((node as UnsupportedNode).type.startsWith("chart:"))
    }

    @Test
    fun `unsafe media source degrades`() {
        val json = nodes(
            JSONObject().apply { put("type", "video"); put("source", "http://insecure/v.mp4") },
        )
        assertTrue((rootOf(json) as ColumnNode).children[0] is UnsupportedNode)
    }

    @Test
    fun `depth limit degrades deep branch`() {
        // 逐层包 20 层 column，超过上限的那一层应变成占位而不是丢弃
        var inner = JSONObject().apply { put("type", "text"); put("body", "deep") }
        repeat(20) {
            inner = JSONObject().apply {
                put("type", "column")
                put("children", JSONArray().apply { put(inner) })
            }
        }
        val result = JsTypeConverter.toUiTree(inner.toString())
        assertTrue("根节点应仍可解析", result.root != null)
        // 深到底部必然出现占位（上限 12 层）
        var node: com.lifeboard.plugin.api.PluginNode? = result.root
        var found = false
        repeat(20) {
            if (node is ColumnNode) {
                node = node.children.firstOrNull()
                if (node is UnsupportedNode) found = true
            }
        }
        assertTrue("超深的分支应降级为占位", found)
    }

    @Test
    fun `invalid json yields null root`() {
        assertNull(rootOf("not json"))
        assertNull(rootOf("{}"))
        assertNull(rootOf(""))
        assertNull(rootOf("null"))
    }

    /** mihealth 的完整产物片段：四种区块都要能解析出来 */
    @Test
    fun `full mihealth payload parses`() {
        val json = nodes(
            JSONObject().apply { put("type", "statusCard"); put("label", "登录状态"); put("value", "已登录") },
            JSONObject().apply { put("type", "statusCard"); put("label", "最近一次步数"); put("value", "8000 步") },
            JSONObject().apply {
                put("type", "chart")
                put("title", "近 7 日步数")
                put("chart", JSONObject().apply {
                    put("kind", "bar")
                    put("bars", JSONArray().apply { put(JSONObject().apply { put("label", "11-17"); put("value", 8000) }) })
                })
            },
            JSONObject().apply {
                put("type", "row")
                put("card", false)
                put("children", JSONArray().apply {
                    put(JSONObject().apply { put("type", "button"); put("label", "立即同步"); put("actionId", "sync"); put("primary", true) })
                })
            },
        )
        val children = (rootOf(json) as ColumnNode).children
        assertEquals(4, children.size)
        assertTrue(children[0] is StatusCardNode)
        assertTrue(children[1] is StatusCardNode)
        assertTrue(children[2] is ChartNode)
        assertTrue(children[3] is RowNode)
    }
}
