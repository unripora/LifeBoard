package com.lifeboard.plugin.api

/**
 * 一个可以「要么给静态值、要么指向宿主数据源」的字段。
 *
 * ## 为什么需要它
 *
 * 会话消息由宿主持有并通过 `StateFlow` 持续推送。若只有静态值，插件每来一条
 * 消息就得重新走一次 `detailUi`——那等于把推送通道退回轮询，而且插件无法感知
 * 「用户正在看」这类宿主状态。
 *
 * 绑定让插件在节点树里写 `{binding:'session:main:messages'}` 就把这块数据
 * 交给宿主托管：消息变了宿主自己重组，插件不必重画整页。
 *
 * ## 为什么是「值或引用」二选一，而不是「值 + 可选引用」
 *
 * 两者同时给时该以谁为准没有正确答案，索性让插件只能说一种。
 *
 * ## 哪几个字段可以绑定
 *
 * 只有 `ListNode.items`、`TextNode.body`、`StatusCardNode.value`、`InputNode.enabled`
 * 用 [Bound]。**不允许出现在任意字段上**——那会滑向「表达式语言」，
 * 而表达式语言需要解析器、作用域、错误处理，无法收敛。
 */
sealed interface Bound<out T> {

    /** 插件直接给值。绝大多数字段都是这种 */
    data class Static<T>(val value: T) : Bound<T>

    /**
     * 指向宿主数据源。
     *
     * @property ref 形如 `<source>:<key>:<field>`，如 `session:main:messages`
     */
    data class Ref<T>(val ref: String) : Bound<T>
}

/**
 * 已解析的绑定引用。
 *
 * 三段式是本设计的核心简化：不定义表达式语法，只允许「取某个数据源的某个具名字段」。
 * 格式不对时 [parse] 返回 null，由调用方降级为静态默认值——**绝不抛异常**，
 * 因为插件传来的 JSON 不可信，一个格式错误不该让整页打不开。
 */
data class BindingRef(
    /** 数据源类型，如 `session`。宿主按它查表；未知则降级为不可用 */
    val source: String,
    /** 数据源实例 key，如 `main`（会话 id） */
    val key: String,
    /** 字段名，如 `messages` / `status` / `inputEnabled` */
    val field: String,
) {
    /**
     * 规范化形式，供日志与去重使用。
     *
     * 无 key 的源输出 `source::field`（中间留空）——与 [parse] 的三段式校验一致，
     * 这样 `canonical()` 的结果能被 [BindingRef.parse] 原样解析回来。
     * 若写成两段式 `source:field`，往返一次就会被判成格式错。
     */
    fun canonical(): String = source + ":" + key + ":" + field

    companion object {
        /**
         * 解析 `<source>:<key>:<field>`。
         *
         * 段数必须恰好三段，且 source / field 都不能为空——宽松解析会让
         * `session:main:messages:extra` 这种拼错静默生效，排查时很难看出来。
         *
         * ## 两段式为什么**不**在这里放宽
         *
         * `session:only-two` 必须被判成格式错：既有契约与测试都依赖这条，
         * 而且两段形态已经被 `submitTo` 占用（第二段是实例 key，不是字段名）。
         *
         * ## 无实例 key 的源怎么表达
         *
         * 用 **key 为空的三段式**：`sessionList::items`。硬凑
         * `sessionList:*:items` 里那个 `*` 只是占位，还会被误读成通配符。
         * 中间段为空对 [parse] 是合法的——这是 [canonical] 产出的形态，
         * 求值链路要能原样读回来。
         */
        fun parse(raw: String): BindingRef? {
            val parts = raw.trim().split(':')
            if (parts.size != 3) return null
            val source = parts[0].trim()
            val key = parts[1].trim()
            val field = parts[2].trim()
            if (source.isEmpty() || field.isEmpty()) return null
            return BindingRef(source, key, field)
        }

        /**
         * 构造一个「没有实例 key」的绑定引用（如 `sessionList` 的 `items`）。
         *
         * 宿主与解析器用它在内部表达「这个源没有实例概念」；插件在 `detailUi`
         * 里同样写 `{binding:'sessionList::items'}`（中间段留空）。
         *
         * 注意 `sessionList:items` 这种两段写法**仍然**是格式错——见 [parse]。
         * 同一段文本有两种解释，排查时最难判断。
         */
        fun keyless(source: String, field: String): BindingRef =
            BindingRef(source, KEYLESS, field)

        /**
         * 解析 `<source>:<key>` 形式的目标（输入框的 `submitTo`）。
         *
         * 与 [parse] 的区别是只要两段：提交是「把这句输入投给哪个数据源实例」，
         * 不涉及具体字段。字段名固定填 [FIELD_SUBMIT]，让调用方看起来仍是一个
         * 完整的 [BindingRef]，不必为「有 key 没 field」再定义一个类型。
         */
        fun parseTarget(raw: String): BindingRef? {
            val parts = raw.trim().split(':')
            if (parts.size != 2) return null
            val source = parts[0].trim()
            val key = parts[1].trim()
            if (source.isEmpty() || key.isEmpty()) return null
            return BindingRef(source, key, FIELD_SUBMIT)
        }

        /**
         * 「该源没有实例 key」的标记。
         *
         * 用空串而不是 `*`：`*` 看起来像通配符，会让人以为它有匹配语义，
         * 而这里表达的是「不存在这个概念」，空串最准确。
         */
        const val KEYLESS = ""

        /** `submitTo` 解析出来的 [field] 占位值。它不对应任何可读字段 */
        const val FIELD_SUBMIT = "submit"
    }

    /** 该绑定是否指向一个没有实例 key 的源（如 `sessionList` 的 `items`） */
    val isKeyless: Boolean get() = key == KEYLESS
}
