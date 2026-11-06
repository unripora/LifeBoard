package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `.ext` 包的安装与元数据读取。
 *
 * 依赖真实文件系统与 `PackageManager.getPackageArchiveInfo`，
 * 因此放在 androidTest：JVM 单测没有 PackageManager，只能验证纯规则部分。
 */
@RunWith(AndroidJUnit4::class)
class PluginInstallerTest {

    private lateinit var context: Context
    private val fileName = "test-plugin.ext"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        PluginInstaller.pluginsDir(context).listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        PluginInstaller.pluginsDir(context).listFiles()?.forEach { it.delete() }
    }

    @Test
    fun install_writesAtomically_andLeavesNoPartFile() {
        val source = File(context.cacheDir, "incoming.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val installed = PluginInstaller.install(source, context, fileName)

        assertNotNull(installed)
        assertTrue(installed!!.exists())
        assertEquals(fileName, installed.name)
        // .part 必须已被 rename 掉，否则会被误认为一次未完成的写入
        assertFalse(File(PluginInstaller.pluginsDir(context), fileName + ".ext.part").exists())
        source.delete()
    }

    @Test
    fun install_overwritesPreviousContent() {
        val first = File(context.cacheDir, "first.bin").apply { writeText("old") }
        val second = File(context.cacheDir, "second.bin").apply { writeText("new-content") }

        PluginInstaller.install(first, context, fileName)
        val installed = PluginInstaller.install(second, context, fileName)

        assertEquals("new-content", installed!!.readText())
        first.delete()
        second.delete()
    }

    @Test
    fun listArchives_ignoresPartFilesAndDirectories() {
        PluginInstaller.pluginsDir(context).resolve("good.ext").writeText("x")
        PluginInstaller.pluginsDir(context).resolve("broken.ext.part").writeText("x")
        PluginInstaller.pluginsDir(context).resolve("some-plugin-dir").mkdirs()

        val names = PluginInstaller.listArchives(context).map { it.name }

        assertEquals(listOf("good.ext"), names)
    }

    @Test
    fun readArchiveInfo_returnsNullForNonApk() {
        val fake = PluginInstaller.pluginsDir(context).resolve("fake.ext")
            .apply { writeText("this is not an apk") }

        assertNull(PluginInstaller.readArchiveInfo(context, fake))
    }

    @Test
    fun sha256_isStableUppercaseHex() {
        val a = PluginInstaller.sha256(byteArrayOf(0x01, 0x02))
        val b = PluginInstaller.sha256(byteArrayOf(0x01, 0x02))

        assertEquals(a, b)
        // 大写十六进制，长度 64；格式变了会让已信任的插件全部失效
        assertEquals(64, a.length)
        assertEquals(a.uppercase(), a)
    }

    @Test
    fun remove_deletesArchive() {
        val source = File(context.cacheDir, "incoming.bin").apply { writeText("x") }
        PluginInstaller.install(source, context, fileName)

        assertTrue(PluginInstaller.remove(context, fileName))
        assertFalse(File(PluginInstaller.pluginsDir(context), fileName).exists())
        source.delete()
    }
}
