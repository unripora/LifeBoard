package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [HostPluginContext] 的真机验证：确认插件拿到的是「只认自己」的存取口，
 * 以及私有目录确实落在本插件命名的路径下。
 */
@RunWith(AndroidJUnit4::class)
class HostPluginContextTest {

    private lateinit var context: Context

    private val pluginA = "com.example.plugin.alpha"
    private val pluginB = "com.example.plugin.beta"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        runBlocking {
            CredentialVault.clearPlugin(context, pluginA)
            CredentialVault.clearPlugin(context, pluginB)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            CredentialVault.clearPlugin(context, pluginA)
            CredentialVault.clearPlugin(context, pluginB)
        }
        HostPluginContext.pluginDataDir(context, pluginA).deleteRecursively()
        HostPluginContext.pluginDataDir(context, pluginB).deleteRecursively()
    }

    @Test
    fun credentialStore_isBoundToOwningPluginId() = runBlocking {
        val a = HostPluginContext(pluginA, context)
        val b = HostPluginContext(pluginB, context)

        a.credentials.put("token", "token-of-a")
        b.credentials.put("token", "token-of-b")

        // 两个插件用同一个 slot 名，互不影响
        assertEquals("token-of-a", a.credentials.get("token"))
        assertEquals("token-of-b", b.credentials.get("token"))
    }

    @Test
    fun clear_onlyAffectsOwnPlugin() = runBlocking {
        val a = HostPluginContext(pluginA, context)
        val b = HostPluginContext(pluginB, context)

        a.credentials.put("token", "a")
        b.credentials.put("token", "b")

        a.credentials.clear()

        assertNull(a.credentials.get("token"))
        assertEquals("b", b.credentials.get("token"))

        // 直接读库确认 B 的行还在
        assertEquals(
            listOf("token"),
            CredentialVault.slots(context, pluginB),
        )
    }

    @Test
    fun remove_removesOnlyThatSlot() = runBlocking {
        val a = HostPluginContext(pluginA, context)
        a.credentials.put("token", "t")
        a.credentials.put("refresh", "r")

        a.credentials.remove("token")

        assertNull(a.credentials.get("token"))
        assertEquals("r", a.credentials.get("refresh"))
    }

    @Test
    fun filesDir_isInsideOwnPluginDirectory() {
        val a = HostPluginContext(pluginA, context)

        assertTrue(a.filesDir.exists())
        assertTrue(a.filesDir.isDirectory)
        assertTrue(
            a.filesDir.absolutePath.contains("/plugins/$pluginA/"),
        )
        // 与别的插件目录不重叠
        assertFalse(a.filesDir.absolutePath.contains("/$pluginB/"))
    }

    @Test
    fun pluginDataDir_rejectsTraversalIds() {
        val unsafe = listOf("", " ", ".", "..", "../evil", "a/b", "a\\b", "a:b")
        unsafe.forEach { id ->
            val rejected = runCatching { HostPluginContext.pluginDataDir(context, id) }.isFailure
            assertTrue("应拒绝非法 pluginId：$id", rejected)
        }

        // 正常反向域名式 id 不报错
        val ok = runCatching { HostPluginContext.pluginDataDir(context, "com.example.ok") }
        assertTrue(ok.isSuccess)
    }
}
