package com.lifeboard.shared.plugin

/**
 * 插件包元数据的通用视图。
 *
 * ## 为什么需要这层抽象
 *
 * 宿主对「插件包」的诉求是稳定的：需要一个身份来支撑信任判定、防降级与展示。
 * 把这几件事提成接口，而不是让加载器直接持有具体包类型，加载、信任判定、UI
 * 就不必知道包长什么样。
 *
 * ## 唯一实现：`.jsplugin`
 *
 * 目前只有 `.jsplugin`（ZIP + JS）一种包格式。历史上还并行过 Android 的
 * `.ext`（改了后缀的 APK，用 `DexClassLoader` 加载），M6b 已移除。
 * 接口保留，因为它是「包」这个概念的唯一边界——将来若再加格式，
 * 实现点只有一处。
 *
 * ## 身份与签名
 *
 * [identity] 是用户信任的对象，必须**稳定且不含版本号**：
 * 同一发布者用同一套指纹发新版可以直接升级，换指纹则必须重新信任。
 * JS 插件的「签名」是包内文件的 SHA-256，靠 [isContentAddressed] 标记语义——
 * 内容变了指纹就变，所以更新不算可疑替换。
 */
interface PluginPackageInfo {

    /** 包标识：取自 manifest 的 id */
    val packageName: String

    /** 版本号，用于防降级 */
    val versionCode: Long

    /** 版本名，仅用于展示 */
    val versionName: String

    /**
     * 签名指纹（SHA-256，大写十六进制），可能多于一个。
     *
     * 为空表示无签名：无签名的包一律不收。
     */
    val signatureHashes: List<String>

    /** 是否有签名 */
    val isSigned: Boolean get() = signatureHashes.isNotEmpty()

    /**
     * 签名指纹是否由**包内容**推导而来（而非发布者证书）。
     *
     * 这个区别决定了「指纹变了」该怎么处理：
     * - 内容寻址（JS 插件）：指纹是包内文件的 SHA-256，**改一行脚本就会变**，
     *   这是日常更新而非可疑替换
     * - 非内容寻址：指纹代表发布者身份，换了就是换了人，必须人工介入
     *
     * 早期一律按「可疑替换」硬拒，导致 JS 插件一旦更新就再也装不上、
     * 也删不掉（拒绝态没有提供任何操作入口），用户只能卸载整个应用。
     *
     * 默认 `false` 是保守取值：不声明就按发布者语义处理。唯一实现
     * `JsPluginPackageInfo` 覆写为 `true`。
     */
    val isContentAddressed: Boolean get() = false

    /**
     * 信任身份。
     *
     * 签名集合排序后再拼接，避免同一套签名因包内顺序不同被当成不同身份。
     * 这是 [PluginIdentity] 的唯一生成入口，禁止别处自行拼接。
     */
    val identity: PluginIdentity
        get() = PluginIdentity(packageName, signatureHashes.sorted().toSet())
}

/**
 * 插件包的信任身份。
 *
 * 为什么不是「包名」：包名可以随便改，真正代表发布者的是签名。
 * 两者合起来才能回答「这是不是上次那个插件」。
 */
data class PluginIdentity(
    val packageName: String,
    val signatureHashes: Set<String>,
) {
    /** 写进信任存储的稳定字符串，同样是唯一拼接入口 */
    val key: String
        get() = "$packageName|${signatureHashes.sorted().joinToString(",")}"
}

/**
 * 信任判定的输入：用户已信任的身份与版本记录。
 *
 * @property trustedKeys 已信任的 [PluginIdentity.key] 集合
 * @property installedVersions 已安装插件的版本记录，key 为包名。
 *   用于防降级：签名没变但版本倒退，说明装的是旧包，可能是被替换
 */
data class TrustState(
    val trustedKeys: Set<String>,
    val installedVersions: Map<String, InstalledVersion> = emptyMap(),
)

/**
 * 已安装插件的版本记录。
 *
 * @property versionCode 当前已安装的版本号
 * @property signatureHashes 安装时的签名指纹，用于**更新时校验签名一致性**
 */
data class InstalledVersion(
    val versionCode: Long,
    val signatureHashes: Set<String>,
)

/**
 * 一次信任判定的结论。
 *
 * 三态而不是布尔：UI 需要区分「没签名」（根本不该出现在列表里）、
 * 「签名了但用户还没信任」（弹警告让用户决定）、「已信任」（直接加载）。
 * 用布尔会把前两种压成同一个「不可用」，用户不知道该做什么。
 */
sealed class TrustVerdict {

    /** 已信任，可以加载 */
    data object Trusted : TrustVerdict()

    /** 没签名，拒绝加载且不提供信任入口 */
    data object Unsigned : TrustVerdict()

    /**
     * 有签名但用户尚未信任。
     *
     * @property identity 待信任的身份，UI 据此展示「信任哪个包名下的哪套签名」
     */
    data class Untrusted(val identity: PluginIdentity) : TrustVerdict()

    /**
     * 身份已信任过，但本次的包与已安装记录不一致，属于可疑替换。
     *
     * 具体两种：签名变了，或版本倒退了。
     *
     * @property reason 展示给用户的具体原因
     */
    data class Rejected(val reason: String) : TrustVerdict()
}
