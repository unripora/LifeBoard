package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.PickerCandidate
import com.lifeboard.plugin.api.PickerMode
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * 对话（`chat`）与清单选择（`picker`）两类节点的载荷解析。
 *
 * ## 为什么单独一个文件
 *
 * 这两个节点的解析自成一套：候选 / 消息都可以「静态给」或「绑定到数据源」，
 * 各字段又都有一层兜底。把它们从 `JsUiNodeParser` 里分出来后，节点类型分支
 * 那张大表与这两块载荷解析互不干扰。
 *
 * ## 与 `list.items` 的关键差异
 *
 * 消息在这里**不再被降级成 `TextNode`**：角色、轮次、流式标记全部保留，
 * 由 `ChatNode` 的渲染层统一处理。这正是「分不清谁说的」的根治点。
 */
internal object JsChatPickerParser {

    /**
     * 解析候选清单。
     *
     * 静态分支：`candidates: [{id,label,detail,tone,disabled}, ...]`
     * 绑定分支：`candidates: {binding:'sessionList::items'}`（清单源无实例 key，
     * 中间段留空）或 `{binding:'session:<key>:items'}`
     *
     * 与 `list.items` 一样「二选一」，两种写法不会并存。
     */
    fun PluginJsonObject.parseCandidates(
        budget: JsTypeConverter.UiParseBudget,
    ): Bound<List<PickerCandidate>> {
        val obj = optJSONObject("candidates")
        if (obj != null) {
            val ref = obj.optString("binding").takeIf { it.isNotBlank() }
            val parsed = ref?.let { BindingRef.parse(it) }
            return if (parsed == null) {
                Bound.Static(emptyList())
            } else {
                Bound.Ref<List<PickerCandidate>>(parsed.canonical())
            }
        }
        val arr = optJSONArray("candidates") ?: return Bound.Static(emptyList())
        // 给候选清单的解析也记一份预算：一行 `picker` 里塞几千个候选同样会拖死测量阶段
        val limit = minOf(arr.length(), JsTypeConverter.MAX_LIST_ITEMS)
        val candidates = (0 until limit).mapNotNull { i ->
            arr.optJSONObject(i)?.toPickerCandidate()
        }
        budget.nodeCount += candidates.size
        return Bound.Static(candidates)
    }

    /**
     * 解析一条候选。
     *
     * `id` 为空就整条丢弃：候选 id 是回传的唯一凭据，没有它点了也没人认。
     */
    private fun PluginJsonObject.toPickerCandidate(): PickerCandidate? {
        val id = optString("id").ifBlank { return null }
        if (id.utf8Size() > JsTypeConverter.MAX_MACHINE_FIELD_BYTES) {
            AppLogger.warn(
                "picker 候选 id 超过 ${JsTypeConverter.MAX_MACHINE_FIELD_BYTES} 字节，已丢弃该候选",
            )
            return null
        }
        return PickerCandidate(
            id = id,
            label = truncateDisplay(
                optString("label").ifBlank { id },
                JsTypeConverter.MAX_DISPLAY_TEXT_BYTES,
            ),
            detail = truncateDisplay(optString("detail"), JsTypeConverter.MAX_DISPLAY_TEXT_BYTES),
            tone = optString("tone").toTone(),
            disabled = optBoolean("disabled", false),
        )
    }

    /**
     * 解析对话节点的消息。
     *
     * 静态分支是历史记录；绑定分支由宿主把会话源的消息原样交给渲染层。
     */
    fun PluginJsonObject.parseMessages(
        budget: JsTypeConverter.UiParseBudget,
    ): Bound<List<ChatMessage>> {
        val obj = optJSONObject("messages")
        if (obj != null) {
            val ref = obj.optString("binding").takeIf { it.isNotBlank() }
            val parsed = ref?.let { BindingRef.parse(it) }
            return if (parsed == null) {
                Bound.Static(emptyList())
            } else {
                Bound.Ref<List<ChatMessage>>(parsed.canonical())
            }
        }
        val arr = optJSONArray("messages") ?: return Bound.Static(emptyList())
        val limit = minOf(arr.length(), JsTypeConverter.MAX_CHAT_MESSAGES)
        val messages = (0 until limit).mapNotNull { i -> arr.optJSONObject(i)?.toChatMessage() }
        budget.nodeCount += messages.size
        return Bound.Static(messages)
    }

    /** 解析一条静态消息；角色认不出时按 SYSTEM 处理（最弱化的呈现，不会冒充任一方） */
    private fun PluginJsonObject.toChatMessage(): ChatMessage = ChatMessage(
        role = optString("role").toChatRole(),
        text = truncateDisplay(optString("text"), JsTypeConverter.MAX_DISPLAY_TEXT_BYTES),
        id = optString("id")
            .takeIf { it.utf8Size() <= JsTypeConverter.MAX_MACHINE_FIELD_BYTES }
            .orEmpty(),
        turnId = optString("turnId")
            .takeIf { it.utf8Size() <= JsTypeConverter.MAX_MACHINE_FIELD_BYTES }
            .orEmpty(),
        timeLabel = optStringOrNull("timeLabel"),
        pending = optBoolean("pending", false),
    )

    private fun String.toChatRole(): ChatRole = when (lowercase()) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.ASSISTANT
        else -> ChatRole.SYSTEM
    }

    /** 认不出的 `picker.mode` 退回 MODAL：弹层比就地展开更不容易被误认为静态内容 */
    fun String.toPickerMode(): PickerMode = when (lowercase()) {
        "inline" -> PickerMode.INLINE
        else -> PickerMode.MODAL
    }
}
