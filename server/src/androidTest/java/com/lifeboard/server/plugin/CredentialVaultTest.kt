package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.server.db.LifeBoardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CredentialVault] 的真机验证。
 *
 * 这些断言依赖 Android Keystore（密钥生成与不可导出）、AES-GCM 实际加解密、
 * 以及 Room 实际落盘，纯 JVM 单测无法替代，因此放在 androidTest。
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {

    private lateinit var context: Context

    private val pluginA = "com.example.plugin.alpha"
    private val pluginB = "com.example.plugin.beta"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        // 每个用例从干净状态开始，避免上一轮残留影响断言
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
    }

    @Test
    fun writeThenRead_returnsPlainText() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "secret-token-αβγ")

        assertEquals("secret-token-αβγ", CredentialVault.read(context, pluginA, "token"))
        assertTrue(CredentialVault.has(context, pluginA, "token"))
    }

    @Test
    fun write_overwritesPreviousValue() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "old")
        CredentialVault.write(context, pluginA, "token", "new")

        assertEquals("new", CredentialVault.read(context, pluginA, "token"))
        assertEquals(listOf("token"), CredentialVault.slots(context, pluginA))
    }

    @Test
    fun storedRow_isNotPlainText() = runBlocking {
        val plain = "plain-text-must-not-appear-in-db"
        CredentialVault.write(context, pluginA, "token", plain)

        val stored = LifeBoardDatabase.get(context).pluginCredentialDao().get(pluginA, "token")

        assertTrue("落库内容不应为空", stored != null)
        assertNotEquals(plain, stored)
        assertFalse("落库内容不得包含明文片段", stored!!.contains(plain))
    }

    @Test
    fun sameSlot_differentPlugins_areIsolated() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "token-of-a")
        CredentialVault.write(context, pluginB, "token", "token-of-b")

        assertEquals("token-of-a", CredentialVault.read(context, pluginA, "token"))
        assertEquals("token-of-b", CredentialVault.read(context, pluginB, "token"))
    }

    @Test
    fun clearSlot_onlyRemovesThatSlot() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "t")
        CredentialVault.write(context, pluginA, "refresh", "r")

        CredentialVault.clearSlot(context, pluginA, "token")

        assertNull(CredentialVault.read(context, pluginA, "token"))
        assertFalse(CredentialVault.has(context, pluginA, "token"))
        assertEquals("r", CredentialVault.read(context, pluginA, "refresh"))
    }

    @Test
    fun clearPlugin_removesAllSlotsOfThatPluginOnly() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "t")
        CredentialVault.write(context, pluginA, "refresh", "r")
        CredentialVault.write(context, pluginB, "token", "other")

        val removed = CredentialVault.clearPlugin(context, pluginA)

        assertEquals(2, removed)
        assertTrue(CredentialVault.slots(context, pluginA).isEmpty())
        assertNull(CredentialVault.read(context, pluginA, "token"))
        assertNull(CredentialVault.read(context, pluginA, "refresh"))
        assertEquals("other", CredentialVault.read(context, pluginB, "token"))
    }

    @Test
    fun read_missingSlot_returnsNull() = runBlocking {
        assertNull(CredentialVault.read(context, pluginA, "never-written"))
        assertFalse(CredentialVault.has(context, pluginA, "never-written"))
    }

    @Test
    fun corruptedCipherText_isTreatedAsAbsent() = runBlocking {
        CredentialVault.write(context, pluginA, "token", "t")
        LifeBoardDatabase.get(context).pluginCredentialDao().upsert(
            com.lifeboard.server.db.PluginCredentialEntity(
                pluginId = pluginA,
                slot = "token",
                cipherText = "bm90LWEtcmVhbC1ibG9i",
            ),
        )

        // 解密失败按「没有凭据」处理，不抛异常给插件
        assertNull(CredentialVault.read(context, pluginA, "token"))
    }
}
