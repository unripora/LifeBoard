package com.lifeboard.desktop.apps

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.lifeboard.shared.platform.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.filechooser.FileSystemView

/**
 * Windows 侧的「已安装应用」查询（M4-4.6）。
 *
 * ## 数据来源为什么是开始菜单而不是注册表
 *
 * `HKLM\...\Uninstall` 里只有「装了皮、卸得掉的程序」，绿色版、便携版、微软商店应用
 * 都不在里面；而**开始菜单里的快捷方式恰好等于「用户能双击启动的东西」**——
 * 这正是规则要监听的对象。所以扫两个目录：
 *
 * - `%ProgramData%\Microsoft\Windows\Start Menu\Programs`（所有用户）
 * - `%APPDATA%\Microsoft\Windows\Start Menu\Programs`（当前用户）
 *
 * ## 标识符为什么用 .exe 路径
 *
 * 快捷方式本身不是稳定的身份（同名快捷方式可以指向不同程序），所以解开 `.lnk`
 * 拿真实目标路径。解 `.lnk` 走一次 `WScript.Shell`（Windows 自带 COM 组件），
 * 比自己解析二进制快捷方式格式可靠得多。
 *
 * **代价**：解几百个快捷方式要起一次 PowerShell，约 1～2 秒。所以 [list] 的结果
 * 由调用方按会话缓存（规则编辑页只在打开选择器时拉一次），不做定时刷新。
 *
 * ## 已知局限
 *
 * - 快捷方式指向 `explorer.exe` 或 `.url` 时解不出有意义的 exe 路径，直接跳过
 * - 只扫开始菜单，桌面上的快捷方式看不到
 * - 微软商店应用（`WindowsApps` 下的 UWP）没有可直接执行的 exe 路径，也跳过
 *
 * 漏掉的程序都能通过编辑页的「手动填写标识符」补上，所以不追求覆盖率，宁可少而准。
 */
class DesktopAppCatalog : AppCatalog {

    override suspend fun list(): List<AppCatalog.AppEntry> = withContext(Dispatchers.IO) {
        val links = startMenuRoots()
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension.equals("lnk", true) }
                    .toList()
            }
        if (links.isEmpty()) return@withContext emptyList()

        resolveTargets(links)
            .mapNotNull { (link, target) ->
                if (!target.endsWith(".exe", ignoreCase = true)) return@mapNotNull null
                if (!File(target).exists()) return@mapNotNull null
                AppCatalog.AppEntry(
                    key = target,
                    label = link.nameWithoutExtension,
                    detail = target,
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.label }
    }

    override suspend fun icon(key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(iconCache) {
            if (iconCache.containsKey(key)) return@withContext iconCache[key]
            val bitmap = runCatching { shellIcon(key) }.getOrNull()
            iconCache[key] = bitmap
            bitmap
        }
    }

    override suspend fun label(key: String): String = withContext(Dispatchers.IO) {
        // 从路径取文件名去掉扩展名就足够可读，不必为显示名称再扫一遍开始菜单
        File(key).nameWithoutExtension.ifBlank { key }
    }

    private fun startMenuRoots(): List<File> {
        val programData = System.getenv("ProgramData")
        val appData = System.getenv("APPDATA")
        return buildList {
            programData?.let { add(File(it, "Microsoft\\Windows\\Start Menu\\Programs")) }
            appData?.let { add(File(it, "Microsoft\\Windows\\Start Menu\\Programs")) }
        }.filter { it.isDirectory }
    }

    /**
     * 批量解开快捷方式：一次 PowerShell 处理全部，而不是每个文件起一次进程。
     *
     * 返回 `快捷方式文件 → 目标路径`。单个失败不影响其余——
     * 坏掉的 `.lnk` 在真实机器上并不少见，不该让整张列表报错。
     */
    private fun resolveTargets(links: List<File>): List<Pair<File, String>> {
        val script = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            ${'$'}shell = New-Object -ComObject WScript.Shell
            ${'$'}in = [Console]::In.ReadToEnd()
            ${'$'}in -split "`n" | ForEach-Object {
                if (${'$'}_ -eq '') { return }
                ${'$'}t = ${'$'}shell.CreateShortcut(${'$'}_).TargetPath
                if (${'$'}t) { Write-Output (${'$'}_ + "`t" + ${'$'}t) }
            }
        """.trimIndent()

        return runCatching {
            val process = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start()

            process.outputStream.bufferedWriter().use { writer ->
                links.forEach { writer.write(it.absolutePath); writer.newLine() }
            }

            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val byPath = links.associateBy { it.absolutePath }
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@mapNotNull null
                val link = byPath[parts[0]] ?: return@mapNotNull null
                link to parts[1].trim()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 取 exe 的图标。
     *
     * `FileSystemView` 给的是 16×16 的系统小图标，放大到 44dp 会糊，
     * 所以按 3 倍尺寸重绘一次再转成 Compose 位图；代价是细节略软，
     * 但比为每个图标去解析 PE 资源段省事得多。
     */
    private fun shellIcon(path: String): ImageBitmap? {
        val icon: Icon = FileSystemView.getFileSystemView().getSystemIcon(File(path)) ?: return null
        val image = (icon as? javax.swing.ImageIcon)?.image ?: return null
        val size = ICON_PIXELS
        val bmp = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = bmp.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(image, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return bmp.toComposeImageBitmap()
    }

    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    private companion object {
        /** 解快捷方式最多等 20 秒，超时按「一个都没解出来」处理，避免界面卡死 */
        const val RESOLVE_TIMEOUT_SECONDS = 20L

        /** 列表图标显示 44dp，按 3 倍解码 */
        const val ICON_PIXELS = 128
    }
}
