package com.yunx.app.util

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * Windows 右键菜单用 JNA 调 Win32 TrackPopupMenu（中文正常、暗色跟随系统）；
 * macOS 使用 AWT PopupMenu（菜单栏托盘由系统绘制）。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

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
        if (!SystemTray.isSupported()) return
        if (trayIcon != null) uninstall()

        val isMac = System.getProperty("os.name").orEmpty().lowercase()
            .let { it.contains("mac") || it.contains("darwin") }
        val popup = PopupMenu()
        if (isMac) {
            popup.add(MenuItem("显示主窗口").apply {
                addActionListener { onShowMac?.invoke() ?: onLeftClick() }
            })
            popup.addSeparator()
            popup.add(MenuItem("退出").apply {
                addActionListener { onExitMac?.invoke() }
            })
        }
        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            // Windows 必须设 PopupMenu 才能收到右键事件；macOS 直接使用系统托盘菜单
            popupMenu = popup
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onLeftClick()
                }
                override fun mousePressed(e: MouseEvent) {
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
}
