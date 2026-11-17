package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.server.db.DatabaseProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端的凭据保管入口（M4-4.3）。
 *
 * ## 现在它只是一层「Context → 实例」的转接
 *
 * 编排逻辑（读库 → 解密、写入 → 加密 → 落库、清空、列槽位）已经搬到
 * `shared` 的 [com.lifeboard.shared.repository.CredentialVault]，两端共用同一份。
 * 真正平台相关的密钥保管在 [AndroidKeystoreCodec]。
 *
 * 保留这个对象而不是让调用方直接 new 一个 `CredentialVault`，有两个原因：
 *
 * 1. **调用点有七处**（`AndroidPluginHost`、`HostPluginContext`、两个 ViewModel、
 *    两个测试类），逐处改成构造注入只是把 `Context` 搬运一遍，没有信息增量
 * 2. `Context` 本来就该在装配层消失，而不是扩散到每个调用方
 *
 * 所以这里保持原来的静态 API 不变，内部委托给共享实现。
 * 桌面端没有 `Context`，直接自己 new `CredentialVault(store, codec)`。
 */
object CredentialVault {

    @Volatile
    private var vault: com.lifeboard.shared.repository.CredentialVault? = null

    @Volatile
    private var codec: AndroidKeystoreCodec? = null

    /**
     * 缓存的共享实例。
     *
     * 缓存是因为 [AndroidKeystoreCodec] 里持有 Keystore 查询结果的路由，
     * 每次调用都重新 `KeyStore.getInstance(...).load(null)` 虽然正确但要读系统密钥库，
     * 在插件详情页反复刷新槽位列表时是不必要的开销。
     */
    private fun vault(context: Context): com.lifeboard.shared.repository.CredentialVault {
        vault?.let { return it }
        return synchronized(this) {
            vault ?: run {
                val ctx = context.applicationContext
                // codec 与 vault 必须成对缓存：单独取 codec 会让两份 codec 各自去建密钥
                val c = AndroidKeystoreCodec(ctx).also { codec = it }
                com.lifeboard.shared.repository.CredentialVault(
                    DatabaseProviders.pluginStore(ctx),
                    c,
                ).also { vault = it }
            }
        }
    }

    suspend fun read(context: Context, pluginId: String, slot: String): String? =
        withContext(Dispatchers.IO) { vault(context).read(pluginId, slot) }

    suspend fun write(context: Context, pluginId: String, slot: String, value: String) =
        withContext(Dispatchers.IO) { vault(context).write(pluginId, slot, value) }

    suspend fun clearPlugin(context: Context, pluginId: String): Int =
        withContext(Dispatchers.IO) { vault(context).clearPlugin(pluginId) }

    suspend fun clearSlot(context: Context, pluginId: String, slot: String) =
        withContext(Dispatchers.IO) { vault(context).clearSlot(pluginId, slot) }

    suspend fun slots(context: Context, pluginId: String): List<String> =
        withContext(Dispatchers.IO) { vault(context).slots(pluginId) }

    suspend fun has(context: Context, pluginId: String, slot: String): Boolean =
        withContext(Dispatchers.IO) { vault(context).has(pluginId, slot) }
}
