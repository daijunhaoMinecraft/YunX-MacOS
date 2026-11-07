package com.yunx.app.util

import java.awt.Color
import java.awt.EventQueue
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * Windows 右键菜单用 JNA 调 Win32 TrackPopupMenu（中文正常、暗色跟随系统）；
 * macOS 不使用 AWT TrayIcon 的 PopupMenu —— 它会在 AppKit 主线程进入
 * NSMenuTrackingSession，部分 Compose/JCEF 组合下菜单窗口不显示，应用随即“未响应”。
 * macOS 改为在图标点击后显示 Swing JPopupMenu（独立弹窗，不占用状态栏菜单循环）。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null
    private var macPopup: JPopupMenu? = null
    private var macPopupAnchor: Window? = null

    private const val TRAY_TOOLTIP = "云析 YunX-Desktop"
    private const val MAC_TRAY_ICON_SIZE = 42

    private val isMac: Boolean by lazy {
        System.getProperty("os.name").orEmpty().lowercase()
            .let { it.contains("mac") || it.contains("darwin") }
    }

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 图标
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键单击回调（屏幕坐标 x, y）
     * @param onShowMac 仅 macOS：托盘菜单「显示主窗口」
     * @param onExitMac 仅 macOS：托盘菜单「退出」
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit,
        onShowMac: (() -> Unit)? = null,
        onExitMac: (() -> Unit)? = null
    ) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater {
                install(image, onLeftClick, onRightClick, onShowMac, onExitMac)
            }
            return
        }
        if (!SystemTray.isSupported()) return
        if (trayIcon != null) uninstall()

        if (isMac) {
            macPopup = buildMacPopup(
                onShow = onShowMac ?: onLeftClick,
                onExit = onExitMac
            )
        }

        // Windows 必须给 TrayIcon 挂一个 PopupMenu 才能收到右键事件；
        // macOS 的菜单由 JPopupMenu 负责，不能挂 AWT PopupMenu。
        val popup = if (isMac) null else PopupMenu()
        val trayImage = scaleTrayImage(image)
        val icon = (if (popup != null) {
            TrayIcon(trayImage, TRAY_TOOLTIP, popup)
        } else {
            TrayIcon(trayImage, TRAY_TOOLTIP)
        }).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && !isMac) onLeftClick()
                }
                override fun mousePressed(e: MouseEvent) {
                    if (isMac) {
                        if (e.button == MouseEvent.BUTTON1 || e.button == MouseEvent.BUTTON3) {
                            e.consume()
                            showMacPopup(e.xOnScreen, e.yOnScreen)
                        }
                        return
                    }
                    if (e.button == MouseEvent.BUTTON3) {
                        e.consume()
                        onRightClick(e.xOnScreen, e.yOnScreen)
                    }
                }
            })
        }
        runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    /** 移除托盘图标 */
    fun uninstall() {
        disposeMacPopup()
        macPopup = null
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * 走 AWT 托盘图标的原生气泡（Windows 10/11 会归入通知中心）。
     * 注意：托盘图标必须处于可见状态，否则 Windows 不会显示。
     * 这里不能用 Compose 的 TrayState —— 托盘图标已改为手写 AWT TrayIcon，没有 TrayState 实例。
     */
    fun showNotification(title: String, message: String) {
        val icon = trayIcon ?: return
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w("YunX-Tray", "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()

    private fun buildMacPopup(onShow: () -> Unit, onExit: (() -> Unit)?): JPopupMenu {
        return JPopupMenu().apply {
            add(JMenuItem("显示主窗口").apply {
                addActionListener { onShow() }
            })
            addSeparator()
            add(JMenuItem("退出").apply {
                addActionListener { onExit?.invoke() }
            })
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = disposeMacAnchor()
                override fun popupMenuCanceled(e: PopupMenuEvent) = disposeMacAnchor()
            })
        }
    }

    private fun showMacPopup(xOnScreen: Int, yOnScreen: Int) {
        val popup = macPopup ?: return
        disposeMacAnchor()
        val anchor = JWindow().apply {
            background = Color.BLACK
            opacity = 0.01f
            focusableWindowState = false
            setSize(1, 1)
            setLocation(xOnScreen, yOnScreen)
            isVisible = true
        }
        macPopupAnchor = anchor
        popup.show(anchor, 0, 0)
    }

    private fun disposeMacAnchor() {
        macPopupAnchor?.dispose()
        macPopupAnchor = null
    }

    private fun disposeMacPopup() {
        macPopup?.isVisible = false
        disposeMacAnchor()
    }

    /**
     * macOS 菜单栏处于 Retina 2x 环境：把图标先生成 42px（21pt @2x），
     * 再交给 AWT autosize。若先缩成 16px 再让 AppKit 拉伸，状态栏图标会明显发糊。
     */
    private fun scaleTrayImage(image: BufferedImage): BufferedImage {
        val size = if (isMac) MAC_TRAY_ICON_SIZE else 16
        if (image.width == size && image.height == size) return image
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { out ->
            val graphics = out.createGraphics()
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    if (isMac) RenderingHints.VALUE_INTERPOLATION_BICUBIC
                    else RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
                )
                graphics.drawImage(image, 0, 0, size, size, null)
            } finally {
                graphics.dispose()
            }
        }
    }
}
