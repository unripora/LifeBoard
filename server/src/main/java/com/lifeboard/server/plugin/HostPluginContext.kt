package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.PluginContext
import java.io.File

/**
 * [PluginContext] 的宿主实现。
 *
 * 关键在于 [pluginId] 是构造时绑定的 `val`，且没有对外暴露可修改它的方法：
 * 插件从 [credentials] 走的每一次存取都会带上这个 pluginId，
 * 接口里没有「传别人的 id」这条路径。
 */
class HostPluginContext(
    override val pluginId: String,
    context: Context,
) : PluginContext {

    private val appContext = context.applicationContext

    override val credentials: CredentialStore = VaultCredentialStore(pluginId, appContext)

    /**
     * 目录在首次访问时才创建。
     *
     * 一次采集会依次调 `fetch` / `health` / `authState`，每次都新建本对象；
     * 若在构造函数里做 [File.mkdirs]，就变成每次采集都查一遍目录存在性。
     * 插件不碰 [filesDir] 时（演示插件就是这样）这一步完全是白费。
     */
    override val filesDir: File by lazy {
        pluginDataDir(appContext, pluginId).also { if (!it.exists()) it.mkdirs() }
    }

    companion object {
        /**
         * 插件私有目录：`filesDir/plugins/{pluginId}/data`。
         *
         * 用 pluginId 拼路径前必须校验：pluginId 来自 `.ext` 的 manifest，
         * 属于外部输入，含 `../` 就能写到别的插件或宿主目录里去。
         */
        fun pluginDataDir(context: Context, pluginId: String): File {
            require(isSafePluginId(pluginId)) { "非法 pluginId：$pluginId" }
            return File(File(File(context.applicationContext.filesDir, "plugins"), pluginId), "data")
        }

        /**
         * pluginId 只允许反向域名式的安全字符。
         *
         * 拒绝空串、`..`、路径分隔符与冒号（冒号是插件 key 的分隔符）：
         * 前两者防目录穿越，后两者防 key 解析歧义。
         */
        fun isSafePluginId(pluginId: String): Boolean =
            pluginId.isNotBlank() &&
                pluginId != "." &&
                pluginId != ".." &&
                pluginId.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
    }
}

/** 把 [CredentialVault] 收窄成「只认本插件」的存取口 */
private class VaultCredentialStore(
    private val pluginId: String,
    private val context: Context,
) : CredentialStore {

    override suspend fun get(slot: String): String? =
        CredentialVault.read(context, pluginId, slot)

    override suspend fun put(slot: String, value: String) {
        CredentialVault.write(context, pluginId, slot, value)
    }

    override suspend fun remove(slot: String) {
        CredentialVault.clearSlot(context, pluginId, slot)
    }

    override suspend fun clear() {
        CredentialVault.clearPlugin(context, pluginId)
    }
}
