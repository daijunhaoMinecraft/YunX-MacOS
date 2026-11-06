package com.yunx.app.util

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 「主窗口位置」遮罩选择器：调起 C# 原生遮罩进程 `YunX-PositionPicker.exe`。
 *
 * 该 exe 以资源形式打进 jar（`yunx/` 前缀）。只在用户点击「设置位置」的这一刻才把它释放成
 * `%TEMP%` 下的一次性文件并立即执行，遮罩退出后马上销毁 —— 非使用期间磁盘上不存在这个原生文件，
 * 发布包目录里也没有任何原生文件。开发态则直接复用 run.ps1 编到仓库根 `build\` 下的产物。
 *
 * 为什么遮罩不放在 Compose 里画：Compose Desktop 的透明窗口在 Windows 上靠分层窗口做逐像素
 * alpha 合成，而 OPENGL/ANGLE 交换链不把 alpha 交给分层窗口 —— 透明区一律被合成为不透明黑。
 * C# 进程用 `UpdateLayeredWindow` 做真正的逐像素透明，遮罩背后就是【实时的桌面本身】：
 * 不是快照、没有黑底，遮罩期间在下面开别的软件照样看得见（完全透明的像素还会自动穿透点击）。
 */
object PositionPicker {

    private const val TAG = "YunX-Picker"
    private const val EXE_NAME = "YunX-PositionPicker.exe"

    /** 用户选定的位置（AWT 逻辑像素） */
    data class Position(val x: Int, val y: Int)

    /**
     * 弹出全屏遮罩，让用户把站位框拖到目标位置。
     *
     * 本方法会阻塞到用户「保存」/「取消」/按 Esc，调用方必须放到后台线程执行。
     *
     * @param scale 物理像素 / 逻辑像素（Compose 的 density），遮罩进程据此换算 DPI
     * @param reduceMotion 软件渲染或用户开了「减少动画」时为 true，遮罩内的翻转 / hover 动效直接落位
     * @return 用户选定的位置；取消、按 Esc 或调起失败返回 null
     */
    fun pick(
        boxWidth: Int,
        boxHeight: Int,
        initialX: Int,
        initialY: Int,
        scale: Float,
        colorRgb: Int,
        reduceMotion: Boolean
    ): Position? {
        if (isMac()) {
            return pickMac(boxWidth, boxHeight, initialX, initialY, colorRgb)
        }
        val resolved = resolveExe()
        if (resolved == null) {
            Log.w(TAG, "未找到 $EXE_NAME，无法设置主窗口位置（jar 内 yunx/$EXE_NAME 资源缺失，且开发态 build\\ 下也没有）")
            return null
        }
        return try {
            runCatching {
                val process = ProcessBuilder(
                    resolved.exe.absolutePath,
                    "--scale", scale.toString(),
                    "--box-w", boxWidth.toString(),
                    "--box-h", boxHeight.toString(),
                    "--x", initialX.toString(),
                    "--y", initialY.toString(),
                    "--color", String.format("%06X", colorRgb and 0xFFFFFF),
                    "--reduce-motion", if (reduceMotion) "1" else "0"
                ).start()

                // 遮罩进程退出时恰好输出一行；用户一直不点则本行一直阻塞（符合预期）
                val line = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()

                val detail = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                if (detail.isNotEmpty()) Log.w(TAG, "遮罩进程 stderr：$detail")

                line?.takeIf { it.startsWith("SAVE ") }?.let { saved ->
                    val parts = saved.removePrefix("SAVE ").trim().split(' ')
                    val x = parts.getOrNull(0)?.toIntOrNull()
                    val y = parts.getOrNull(1)?.toIntOrNull()
                    if (x != null && y != null) Position(x, y) else null
                }
            }.onFailure { Log.w(TAG, "遮罩进程异常：$it") }.getOrNull()
        } finally {
            // 从 jar 一次性释放出来的临时 exe：遮罩一退出立即销毁
            if (resolved.temporary) deleteQuietly(resolved.exe)
        }
    }

    /**
     * macOS 原生遮罩：全屏半透明 Swing 窗口，拖动站位框后保存。
     * macOS 上 Compose 的透明窗口同样不可靠，因此用不透明暗色遮罩 + 自定义绘制。
     */
    private fun pickMac(
        boxWidth: Int,
        boxHeight: Int,
        initialX: Int,
        initialY: Int,
        colorRgb: Int
    ): Position? {
        val result = AtomicReference<Position?>(null)
        val done = CountDownLatch(1)
        SwingUtilities.invokeLater {
            val virtual = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.defaultConfiguration.bounds }
                .reduceOrNull { a, b -> a.union(b) }
                ?: Rectangle(0, 0, 1440, 900)
            val width = boxWidth.coerceAtLeast(320)
            val height = boxHeight.coerceAtLeast(240)
            val startX = initialX.coerceIn(virtual.x, virtual.x + (virtual.width - width).coerceAtLeast(0))
            val startY = initialY.coerceIn(virtual.y, virtual.y + (virtual.height - height).coerceAtLeast(0))
            val frame = JFrame("设置主窗口位置")
            frame.isUndecorated = true
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.bounds = virtual
            frame.isAlwaysOnTop = true
            frame.background = Color(10, 12, 18)

            val accent = Color((colorRgb shr 16) and 0xFF, (colorRgb shr 8) and 0xFF, colorRgb and 0xFF)
            val overlay = object : JPanel(null) {
                var box = Rectangle(startX - virtual.x, startY - virtual.y, width, height)
                var dragOffsetX = 0
                var dragOffsetY = 0

                init {
                    isOpaque = true
                    background = Color(10, 12, 18)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mousePressed(e: java.awt.event.MouseEvent) {
                            dragOffsetX = e.x - box.x
                            dragOffsetY = e.y - box.y
                        }
                    })
                    addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                        override fun mouseDragged(e: java.awt.event.MouseEvent) {
                            box.x = (e.x - dragOffsetX)
                                .coerceIn(0, (size.width - box.width).coerceAtLeast(0))
                            box.y = (e.y - dragOffsetY)
                                .coerceIn(0, (size.height - box.height).coerceAtLeast(0))
                            repaint()
                        }
                    })
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(4, 7, 12, 170)
                    g2.fillRect(0, 0, width, height)
                    g2.color = Color(255, 255, 255, 210)
                    g2.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
                    g2.drawString("拖动窗口框到主窗口下次出现的位置", 24, 34)
                    g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                    g2.color = Color(210, 215, 225)
                    g2.drawString("Enter 保存 · Esc 取消 · 鼠标拖动调整", 24, 56)

                    g2.color = accent
                    g2.stroke = BasicStroke(3f)
                    g2.drawRoundRect(box.x, box.y, box.width, box.height, 14, 14)
                    g2.color = Color(accent.red, accent.green, accent.blue, 42)
                    g2.fillRoundRect(box.x, box.y, box.width, box.height, 14, 14)
                    val tip = "云析主窗口 ${box.x + virtual.x}, ${box.y + virtual.y}"
                    g2.color = Color.WHITE
                    g2.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
                    g2.drawString(tip, box.x + 18, box.y + 28)
                    g2.dispose()
                }
            }

            fun save() {
                result.set(Position(overlay.box.x + virtual.x, overlay.box.y + virtual.y))
                frame.dispose()
                done.countDown()
            }

            fun cancel() {
                frame.dispose()
                done.countDown()
            }

            val bar = JPanel(FlowLayout(FlowLayout.CENTER, 10, 8)).apply {
                isOpaque = false
                add(JLabel("设置主窗口启动位置").apply {
                    foreground = Color(235, 238, 245)
                    font = font.deriveFont(Font.PLAIN, 13f)
                })
                add(JButton("取消").apply { addActionListener { cancel() } })
                add(JButton("保存位置").apply { addActionListener { save() } })
            }
            overlay.add(bar).apply {
                setBounds(
                    (virtual.width / 2 - 190).coerceAtLeast(16),
                    virtual.height - 68,
                    380,
                    48
                )
            }

            val root = JPanel(BorderLayout()).apply { add(overlay, BorderLayout.CENTER) }
            frame.contentPane = root
            frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel")
            frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "save")
            frame.rootPane.actionMap.put("cancel", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = cancel()
            })
            frame.rootPane.actionMap.put("save", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = save()
            })
            frame.isVisible = true
            frame.toFront()
            frame.requestFocus()
        }
        done.await()
        return result.get()
    }

    /** 解析结果：[temporary] 为 true 表示该 exe 是从 jar 一次性释放的，用完必须删除 */
    private data class Resolved(val exe: File, val temporary: Boolean)

    /** 找到可执行文件；找不到返回 null */
    private fun resolveExe(): Resolved? {
        // ① 环境变量覆盖（调试 / 自定义部署用）
        System.getenv("YUNXPC_PICKER")?.takeIf { it.isNotBlank() }?.let { p ->
            File(p).takeIf { it.isFile }?.let { return Resolved(it, temporary = false) }
        }
        // ② 打包态：点击这一刻才从 jar 释放一份一次性 exe 到 %TEMP%，由 pick() 用完即删
        extractFromJar()?.let { return Resolved(it, temporary = true) }
        // ③ 开发态：run.ps1 把 exe 编到仓库根 build\ 下，从 user.dir 向上逐级找
        return devCandidates().firstOrNull { it.isFile }?.let { Resolved(it, temporary = false) }
    }

    /** 从 classpath 解出一份一次性 exe 到系统临时目录（每次调用都是新文件，用完即删） */
    private fun extractFromJar(): File? = runCatching {
        val bytes = PositionPicker::class.java.classLoader
            .getResourceAsStream("yunx/$EXE_NAME")?.use { it.readBytes() } ?: return null
        File.createTempFile("yunx-picker-", ".exe").apply { writeBytes(bytes) }
    }.onFailure { Log.w(TAG, "释放 $EXE_NAME 到临时目录失败：$it") }.getOrNull()

    /** 删除一次性临时文件；刚退出的进程可能仍短暂占用句柄，重试几次兜底 */
    private fun deleteQuietly(file: File) {
        repeat(5) {
            if (!file.exists() || file.delete()) return
            runCatching { Thread.sleep(50) }
        }
        file.deleteOnExit()
    }

    /** 开发态（gradle run / IDE）的文件系统候选：仓库根 build\ 里由 run.ps1 编出 */
    private fun devCandidates(): List<File> {
        val list = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir", ".")).absoluteFile
        repeat(4) {
            val d = dir ?: return@repeat
            list += File(d, EXE_NAME)
            list += File(File(d, "build"), EXE_NAME)
            dir = d.parentFile
        }
        return list
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase()
            .let { it.contains("mac") || it.contains("darwin") }
}
