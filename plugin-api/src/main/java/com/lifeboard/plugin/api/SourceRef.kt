package com.lifeboard.plugin.api

/**
 * 数据源的统一引用。
 *
 * 引入它的原因：`metric.sourceType` 一直是**字符串**，但业务代码过去用
 * `SourceType.entries.firstOrNull { it.name == ... } ?: MANUAL` 反解，
 * 一旦用户自建数据源，就会被静默降级成「手动打卡」——
 * 面板显示正常、数字永远是 0，且没有任何报错可查。
 *
 * **key 的生成与解析必须全部经过本文件的伴生对象**，禁止在别处自行拼接：
 * 历史 bug 的根因就是各处各写一套拼接逻辑，改了一处漏了另一处。
 *
 * 本类型位于 `:plugin-api`：插件产出数据时需要构造 [Plugin] 分支并给出自己的 key，
 * 而插件不能依赖宿主内部的 `:core`。
 */
sealed interface SourceRef {

    /** 写进 `metric.sourceType` 的键，内置源是枚举名，自定义源是 `CUSTOM_{id}`，插件源见 [pluginKey] */
    val key: String

    /** 展示名称 */
    val displayName: String

    /** 默认量纲 */
    val bucket: MetricBucket

    /** 展示单位 */
    val unit: String

    /** 是否为用户自建源（自建源没有采集器，数据只能来自规则命中） */
    val isCustom: Boolean

    /** 是否为插件源（插件源的数据由插件产出，宿主不内置其采集器） */
    val isPlugin: Boolean get() = false

    /**
     * 内置源。
     *
     * 这里不直接引用宿主内部的 `SourceType` 枚举：`SourceType` 带有目标包名等信息，
     * 属于宿主与注入层的事，插件不需要也不应该看到。
     * 内置源的身份就是它的 [key]（即枚举名），由宿主的 `SourceRegistry` 负责还原成 `SourceType`。
     *
     * @param key 内置源标识，等于 `SourceType` 枚举名，如 `SCREEN_TIME`
     * @param bucket 本次引用的量纲。一个内置源可以产出多个量纲
     *   （小米健康同时给步数与睡眠），此时由采集器为每个量纲各造一个引用并显式指定量纲。
     *   **落库键 [key] 不受量纲影响**，仍是枚举名，历史数据零迁移。
     */
    data class Builtin(
        override val key: String,
        override val displayName: String,
        override val bucket: MetricBucket,
    ) : SourceRef {
        override val unit: String get() = bucket.unit
        override val isCustom: Boolean get() = false
    }

    /** 用户自建源 */
    data class Custom(
        val id: Long,
        override val displayName: String,
        override val bucket: MetricBucket,
    ) : SourceRef {
        override val key: String get() = customKey(id)
        override val unit: String get() = bucket.unit
        override val isCustom: Boolean get() = true
    }

    /**
     * 插件源。
     *
     * 插件是运行时才装进来的，标识不可能追加到宿主的编译期枚举，
     * 所以单独开一个分支，落库仍然只是一个字符串（见 [pluginKey]），表结构零改动。
     *
     * 同一个插件可以产出多个量纲（如小米健康的步数与睡眠），
     * 它们靠 [metricKey] 区分，[bucket] 由插件声明的指标元数据（[MetricSpec]）给出，
     * 与 `metric` 表主键里的 `bucket` 列一一对应。
     *
     * @param pluginId 插件全局唯一标识，反向域名式，如 `dev.foo.mihealth`，不得含 `:`
     * @param metricKey 插件内唯一的指标键，如 `steps`，不得含 `:`
     */
    data class Plugin(
        val pluginId: String,
        val metricKey: String,
        override val displayName: String,
        override val bucket: MetricBucket,
    ) : SourceRef {
        override val key: String get() = pluginKey(pluginId, metricKey)
        override val unit: String get() = bucket.unit
        override val isCustom: Boolean get() = false
        override val isPlugin: Boolean get() = true
    }

    companion object {
        fun customKey(id: Long): String = "CUSTOM_$id"

        /** 从 `CUSTOM_12` 里取出 12，不是自建源返回 null */
        fun parseCustomId(key: String): Long? {
            val suffix = key.removePrefix(CUSTOM_PREFIX)
            if (suffix == key) return null
            return suffix.toLongOrNull()
        }

        /**
         * 插件源写进 `metric.sourceType` 的键：`PLUGIN_{pluginId}:{metricKey}`。
         *
         * 用 `:` 分隔而不是再嵌一层下划线：pluginId 允许含 `.` 与 `_`，
         * 只有 `:` 能保证解析时切分点唯一（两个片段都禁止含 `:`，见 [isValidPluginKeyPart]）。
         */
        fun pluginKey(pluginId: String, metricKey: String): String {
            require(isValidPluginKeyPart(pluginId)) {
                "pluginId 不能为空且不得含 ':'：$pluginId"
            }
            require(isValidPluginKeyPart(metricKey)) {
                "metricKey 不能为空且不得含 ':'：$metricKey"
            }
            return "$PLUGIN_PREFIX$pluginId$PLUGIN_SEPARATOR$metricKey"
        }

        /**
         * 从 `PLUGIN_dev.foo.mihealth:steps` 反解出 `(pluginId, metricKey)`。
         *
         * 不是插件键、或缺任一片段时返回 null——**绝不猜**：
         * 历史上"解析失败静默降级成 MANUAL"正是数字恒为 0 又查不出来的根源。
         */
        fun parsePluginKey(key: String): Pair<String, String>? {
            if (!key.startsWith(PLUGIN_PREFIX)) return null
            val body = key.removePrefix(PLUGIN_PREFIX)
            val sep = body.indexOf(PLUGIN_SEPARATOR)
            if (sep <= 0 || sep == body.length - 1) return null
            val pluginId = body.substring(0, sep)
            val metricKey = body.substring(sep + 1)
            if (!isValidPluginKeyPart(pluginId) || !isValidPluginKeyPart(metricKey)) return null
            // key 里出现第二个分隔符时上面两个片段仍可能合法，这里再兜一次
            if (key.count { it == PLUGIN_SEPARATOR } != 1) return null
            return pluginId to metricKey
        }

        /** 插件键的两个片段都不允许为空或含分隔符，否则无法往返解析 */
        fun isValidPluginKeyPart(part: String): Boolean =
            part.isNotEmpty() && !part.contains(PLUGIN_SEPARATOR)

        private const val CUSTOM_PREFIX = "CUSTOM_"
        private const val PLUGIN_PREFIX = "PLUGIN_"
        private const val PLUGIN_SEPARATOR = ':'
    }
}
