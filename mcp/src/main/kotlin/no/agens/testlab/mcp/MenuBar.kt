package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.runtime.Browser
import no.agens.testlab.services.runtime.LabRuntime
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Testlab's icon in the macOS menu bar (on Windows: the taskbar's notification area) while the
 * server runs: whether it is running, the phones it sees, approvals waiting for a yes, the pages,
 * and restart or quit. The process itself never shows in the Dock ([backgroundApp]). Off with the
 * `menuBarIcon` setting; not on Linux, whose desktops show tray icons unreliably.
 */
class MenuBar private constructor(
    private val rt: LabRuntime,
    private val pageUrl: (String) -> String,
    private val log: PrintStream,
) {
    private val os = HostOs.current()
    /** Under launchd or systemd, which start it again after a non-zero exit. */
    private val asService = System.getenv("TESTLAB_SERVICE") == "1" && os != HostOs.WINDOWS
    private val space = no.agens.testlab.services.Spaces.current().takeIf { it != no.agens.testlab.services.Spaces.DEFAULT }
    private var icon: TrayIcon? = null
    private var waiting = -1
    private val header = MenuItem().apply { isEnabled = false }
    private val phones = MenuItem().apply { isEnabled = false }
    private val approvals = MenuItem().apply { addActionListener { open("approvals") } }

    private fun menu() = PopupMenu().apply {
        add(header); add(phones); add(approvals)
        addSeparator()
        for ((page, label) in listOf("devices" to "Devices", "reports" to "Reports", "setup" to "Setup", "settings" to "Settings", "guide" to "Guide")) {
            add(MenuItem("Open $label…").apply { addActionListener { open(page) } })
        }
        addSeparator()
        if (asService) add(MenuItem("Restart ${AppIdentity.NAME}").apply {
            addActionListener { log.println("restart asked from the menu bar"); remove(); exitProcess(RESTART_EXIT) }
        })
        add(MenuItem("Quit ${AppIdentity.NAME}").apply {
            addActionListener { log.println("quit from the menu bar"); remove(); exitProcess(0) }
        })
    }

    private fun open(page: String) {
        Thread { runCatching { if (!Browser.open(pageUrl(page))) log.println("couldn't open the $page page") } }.start()
    }

    /** Adds the icon or takes it away as the setting says, and brings the menu's lines up to date. */
    private fun refresh() {
        val want = rt.settings.current.menuBarIcon
        val n = rt.approvals.pending.value.size
        val devices = runCatching { rt.listDevices().filter { it.ready } }.getOrDefault(emptyList())
        EventQueue.invokeLater {
            runCatching {
                if (!want) { remove(); return@invokeLater }
                val tray = SystemTray.getSystemTray()
                val i = icon ?: TrayIcon(image(false), AppIdentity.NAME, menu()).apply {
                    isImageAutoSize = true
                    // Windows: a click on the icon opens the devices page (the menu is on right-click).
                    addActionListener { open("devices") }
                    tray.add(this); icon = this
                }
                header.label = "${AppIdentity.NAME} ${AppIdentity.VERSION} · running" + (space?.let { " · space $it" } ?: "")
                phones.label = when (devices.size) {
                    0 -> "No phones connected"
                    else -> "${devices.size} ${if (devices.size == 1) "phone" else "phones"}: " + devices.joinToString { d -> d.model?.replace('_', ' ') ?: d.serial }.let { if (it.length > 60) it.take(57) + "…" else it }
                }
                approvals.label = if (n == 0) "No approvals waiting" else "$n ${if (n == 1) "approval" else "approvals"} waiting…"
                approvals.isEnabled = n > 0
                i.toolTip = AppIdentity.NAME + if (n > 0) ": $n waiting for your yes" else ""
                if ((n > 0) != (waiting > 0)) i.image = image(n > 0)
                waiting = n
            }.onFailure { log.println("menu bar icon: ${it.message}") }
        }
    }

    private fun remove() {
        icon?.let { i -> runCatching { SystemTray.getSystemTray().remove(i) } }
        icon = null; waiting = -1
    }

    /**
     * A test tube, drawn: black on a Mac, where it is a template image (the menu bar colours it for
     * light and dark mode); blue on Windows. A dot in the corner while an approval waits.
     */
    private fun image(badge: Boolean): Image {
        val s = 44
        val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (os == HostOs.MAC) Color.BLACK else Color(0x2F, 0x6F, 0xDB)
        g.stroke = BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // The tube, tilted: a rounded-bottom outline, its rim, and the liquid in the lower half.
        val t = java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(30.0), 22.0, 22.0)
        val tube = Path2D.Double().apply { moveTo(16.0, 6.0); lineTo(16.0, 32.0); quadTo(16.0, 40.0, 22.0, 40.0); quadTo(28.0, 40.0, 28.0, 32.0); lineTo(28.0, 6.0) }
        g.draw(t.createTransformedShape(tube))
        g.draw(t.createTransformedShape(java.awt.geom.Line2D.Double(13.0, 6.0, 31.0, 6.0)))
        val liquid = Path2D.Double().apply { moveTo(16.0, 24.0); lineTo(16.0, 32.0); quadTo(16.0, 40.0, 22.0, 40.0); quadTo(28.0, 40.0, 28.0, 32.0); lineTo(28.0, 24.0); closePath() }
        g.fill(t.createTransformedShape(liquid))
        if (badge) {
            // A gap around the dot keeps it apart from the tube's rim.
            val keep = g.composite
            g.composite = java.awt.AlphaComposite.Clear; g.fillOval(26, -2, 21, 21); g.composite = keep
            if (os != HostOs.MAC) g.color = Color(0xD9, 0x3B, 0x2B)
            g.fillOval(30, 2, 13, 13)
        }
        g.dispose()
        return img
    }

    companion object {
        /** The exit code that asks launchd or systemd for a restart (any failure does; this one says why in the log). */
        const val RESTART_EXIT = 75

        /**
         * On a Mac: the JVM is a background app, never in the Dock or the app switcher, even when it
         * draws images (screenshots, screen pairs); the menu bar icon is a template image. Call it
         * before anything touches AWT.
         */
        fun backgroundApp() {
            if (HostOs.current() != HostOs.MAC) return
            System.setProperty("apple.awt.UIElement", "true")
            System.setProperty("apple.awt.enableTemplateImages", "true")
        }

        /** Shows the icon where the desktop has a menu bar or tray to put it in; refreshes it every few seconds. */
        internal fun start(rt: LabRuntime, pageUrl: (String) -> String, log: PrintStream) {
            if (HostOs.current() == HostOs.LINUX || System.getenv("TESTLAB_NO_MENUBAR") == "1") return
            val ok = runCatching { !GraphicsEnvironment.isHeadless() && SystemTray.isSupported() }.getOrDefault(false)
            if (!ok) { log.println("no menu bar here (no window server for this process): ${AppIdentity.SLUG} status shows how it is doing"); return }
            val bar = MenuBar(rt, pageUrl, log)
            Thread({
                while (true) {
                    bar.refresh()
                    Thread.sleep(5_000)
                }
            }, "menu-bar").apply { isDaemon = true }.start()
        }
    }
}
