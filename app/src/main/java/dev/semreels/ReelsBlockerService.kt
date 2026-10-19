package dev.semreels

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReelsBlockerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    /** O que está sendo mostrado agora; a captura usa isso para recortar as abas. */
    private data class Snapshot(
        val windowId: Int,
        val windowBounds: Rect,
        val barBounds: Rect,
        val tabBounds: List<Rect>,
        val signature: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val finder = BarFinder()
    private lateinit var prefs: SharedPreferences
    private lateinit var overlay: BarOverlay
    private lateinit var config: BarConfig

    private var pending = false
    private var lastStatus: String? = null

    private var current: Snapshot? = null
    private var keptTabs: List<AccessibilityNodeInfo> = emptyList()
    private var captureWanted = false
    private var capturing = false
    private var lastShotAt = 0L
    private var lastRedirectAt = 0L
    // Última aba permitida em que a pessoa estava; diz para que lado ela arrastou.
    private var lastSelectedIndex = -1
    private var captureRetryPosted = false
    private var lastAdSkipAt = 0L

    private lateinit var mask: PageMask
    private var barColor = Color.BLACK
    private var tabCount = 0
    private var blockedPages: List<Int> = emptyList()
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var mutedByUs = false

    override fun onServiceConnected() {
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        config = BarConfig.from(prefs)
        overlay = BarOverlay(this, ::onTabTap, ::onTabLongPress)
        mask = PageMask(this)
        barColor = fallbackColor()
        overlay.setBackground(barColor)
        // Diagnóstico sob demanda pelo computador: adb shell am broadcast -a dev.semreels.DUMP
        registerReceiver(dumpReceiver, IntentFilter(ACTION_DUMP), RECEIVER_EXPORTED)
        schedule()
    }

    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            prefs.edit().putBoolean(Prefs.DUMP_REQUESTED, true).apply()
            schedule(0)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        val fromInstagram = event.packageName == INSTAGRAM_PACKAGE
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> schedule()
            // Arraste entre abas: a máscara precisa acompanhar sem esperar o ciclo normal.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (fromInstagram) {
                rootInActiveWindow?.takeIf { it.packageName == INSTAGRAM_PACKAGE }?.let(::updateMask)
                schedule()
            }
            else -> if (fromInstagram || overlay.isShowing) schedule()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::overlay.isInitialized) return
        overlay.setBackground(fallbackColor())
        requestCapture()
        schedule()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unmute.run()
        if (::prefs.isInitialized) unregisterReceiver(dumpReceiver)
        if (::overlay.isInitialized) overlay.hide()
        if (::mask.isInitialized) mask.hide()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        if (key == Prefs.LAST_STATUS) return
        config = BarConfig.from(p)
        current = null
        schedule()
    }

    // Eventos de conteúdo chegam em rajadas (vídeos, animações); avalia no máximo a cada 50 ms.
    private fun schedule(delayMs: Long = 50) {
        if (pending) return
        pending = true
        handler.postDelayed({
            pending = false
            evaluate()
        }, delayMs)
    }

    // Reavaliação futura que não trava as avaliações rápidas do meio do caminho.
    private fun scheduleLater(delayMs: Long) {
        handler.postDelayed({ schedule(0) }, delayMs)
    }

    private fun evaluate() {
        if (!config.active) {
            hideOverlay()
            hideMask()
            setStatus("Desativado")
            return
        }

        val all = windows
        val instagram = all
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.takeIf { it.packageName == INSTAGRAM_PACKAGE }?.let { w to it } }
            .sortedByDescending { it.first.layer }

        if (instagram.isEmpty()) {
            hideOverlay()
            hideMask()
            setStatus("Instagram não está na tela")
            return
        }

        maybeDump(instagram.first().second)

        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        for ((window, root) in instagram) {
            if (leaveSharedReelIfBlocked(root, screen)) return
            if (skipAdIfShowing(root, screen)) return
            val bar = finder.find(root, config, screen) ?: continue
            tabCount = bar.tabs.size
            blockedPages = bar.tabs.indices.filter { config.isBlocked(bar.tabs[it].node) }
            updateMask(root)
            if (redirectIfBlocked(bar)) return
            val kept = bar.tabs.filterNot { it.hidden }
            if (kept.size == bar.tabs.size || kept.isEmpty()) {
                hideOverlay()
                setStatus("Nada para esconder nesta tela")
                return
            }
            if (topWindowAt(all, bar.bounds)?.id != window.id) {
                hideOverlay()
                setStatus("Barra coberta por outra janela")
                return
            }

            val snapshot = Snapshot(
                windowId = window.id,
                windowBounds = Rect().also(window::getBoundsInScreen),
                barBounds = bar.bounds,
                tabBounds = kept.map { it.bounds },
                signature = kept.joinToString("|") {
                    "${it.bounds.toShortString()} ${it.node.isSelected} ${it.node.contentDescription}"
                } + bar.bounds.toShortString(),
            )
            // Aba selecionada, badge ou posição mudou: precisa recortar os ícones de novo.
            if (snapshot.signature != current?.signature) requestCapture()
            current = snapshot
            keptTabs = kept.map { it.node }

            overlay.show(bar.bounds, kept.size)
            maybeCapture()
            setStatus("Barra com ${kept.size} ícones")
            return
        }

        hideOverlay()
        hideMask()
        setStatus("Barra do Instagram não encontrada nesta tela")
    }

    // As páginas do paginador principal seguem a ordem das abas da barra. Se alguma página
    // bloqueada está aparecendo (mesmo que só uma faixa no meio do arraste), cobre o conteúdo.
    private fun updateMask(root: AccessibilityNodeInfo) {
        if (blockedPages.isEmpty()) {
            hideMask()
            return
        }
        val pager = root.findAccessibilityNodeInfosByViewId(Prefs.MAIN_PAGER_ID).firstOrNull { it.isVisibleToUser }
        val pages = pager?.getChild(0)
        if (pager == null || pages == null) {
            hideMask()
            return
        }
        val tmp = Rect()
        val visible = (0 until pages.childCount).map { i ->
            pages.getChild(i)?.let { it.getBoundsInScreen(tmp); tmp.width() > 0 } == true
        }
        val moving = visible.count { it } >= 2
        val showing = if (pages.childCount == tabCount) {
            blockedPages.any { visible.getOrElse(it) { false } }
        } else {
            // Nem todas as páginas carregadas (a Pesquisa costuma ser criada só quando aparece):
            // sem saber qual é qual, cobre enquanto o arraste estiver no meio de duas páginas.
            moving
        }
        if (showing) showMask(Rect().also(pager::getBoundsInScreen)) else hideMask()
    }

    // O vídeo do Reels começa a tocar assim que a página aparece; silencia a mídia enquanto
    // ele está coberto e devolve o som pouco depois que o Instagram troca de aba e pausa.
    private fun showMask(bounds: Rect) {
        mask.show(bounds, barColor)
        handler.removeCallbacks(unmute)
        if (!mutedByUs && !audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            mutedByUs = true
        }
    }

    private fun hideMask() {
        mask.hide()
        if (mutedByUs) {
            handler.removeCallbacks(unmute)
            handler.postDelayed(unmute, UNMUTE_DELAY_MS)
        }
    }

    private val unmute = Runnable {
        if (mutedByUs) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            mutedByUs = false
        }
    }

    private fun redirectIfBlocked(bar: BarFinder.Bar): Boolean {
        val index = bar.tabs.indexOfFirst { it.node.isSelected }
        if (index < 0) return false
        val selected = bar.tabs[index]
        if (!config.isBlocked(selected.node)) {
            lastSelectedIndex = index
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirectAt < REDIRECT_COOLDOWN_MS) return true
        lastRedirectAt = now

        // Com o ícone escondido, a aba "não existe": arrastar segue para a próxima aba visível.
        // Com o ícone aparecendo, a pessoa tocou nele de propósito: volta para o início.
        val skip = if (selected.hidden) nextAllowedTab(bar.tabs, index) else null
        val target = skip
            ?: bar.tabs.firstOrNull { it.node.viewIdResourceName == Prefs.HOME_TAB_ID }
            ?: bar.tabs.first()
        target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        setStatus(if (skip != null) "Pulou para a próxima aba" else "Voltou para o início")
        scheduleLater(REDIRECT_COOLDOWN_MS)
        return true
    }

    private fun nextAllowedTab(tabs: List<BarFinder.Tab>, from: Int): BarFinder.Tab? {
        val forward = lastSelectedIndex in 0 until from
        val directions = if (forward) listOf(1, -1) else listOf(-1, 1)
        for (step in directions) {
            var i = from + step
            while (i in tabs.indices) {
                val tab = tabs[i]
                if (!tab.hidden && !config.isBlocked(tab.node)) return tab
                i += step
            }
        }
        return null
    }

    // Reel aberto fora da aba Reels (mandado por amigo, link, post do feed): o visualizador
    // aparece na tela sem estar dentro do paginador das abas. Volta para onde a pessoa estava.
    private fun leaveSharedReelIfBlocked(root: AccessibilityNodeInfo, screen: Rect): Boolean {
        if (!config.blockSharedReels) return false
        val open = root.findAccessibilityNodeInfosByViewId(Prefs.CLIPS_VIEWER_ID).any {
            val r = Rect().also(it::getBoundsInScreen)
            it.isVisibleToUser && r.width() > screen.width() / 2 && !isInsideMainPager(it)
        }
        if (!open) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirectAt < REDIRECT_COOLDOWN_MS) return true
        lastRedirectAt = now
        hideOverlay()
        performGlobalAction(GLOBAL_ACTION_BACK)
        setStatus("Saiu de um Reel")
        scheduleLater(REDIRECT_COOLDOWN_MS)
        return true
    }

    // Anúncios vêm junto com o conteúdo; o que dá é reconhecer o rótulo "Patrocinado" e pular.
    // Reels: o rótulo fica dentro do visualizador de Reels, que rola para o próximo vídeo.
    // Stories: o visualizador tem um cabeçalho próprio; num anúncio a descrição dele diz
    // "story patrocinado" e aparece a pílula "Patrocinado" embaixo. Um toque no lado direito
    // passa para o próximo story.
    private fun skipAdIfShowing(root: AccessibilityNodeInfo, screen: Rect): Boolean {
        if (!config.skipStoryAds && !config.skipReelAds) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastAdSkipAt < AD_COOLDOWN_MS) return false

        if (config.skipStoryAds) {
            val header = root.findAccessibilityNodeInfosByViewId(Prefs.STORY_HEADER_ID).firstOrNull { it.isVisibleToUser }
            if (header != null) {
                val headerSaysAd = header.contentDescription?.toString().orEmpty().let { desc ->
                    config.adLabels.any { it.length > 2 && desc.contains(it, ignoreCase = true) }
                }
                if (!headerSaysAd && adLabelBounds(root).isEmpty()) {
                    // A troca de story nem sempre gera evento; confere de novo logo em seguida.
                    scheduleLater(STORY_POLL_MS)
                    return false
                }
                lastAdSkipAt = now
                swipe(screen.width() * 0.92f, screen.height() * 0.45f, screen.width() * 0.92f, screen.height() * 0.45f)
                setStatus("Pulou um anúncio nos Stories")
                return true
            }
        }

        if (!config.skipReelAds) return false
        val labels = adLabelBounds(root)
        if (labels.isEmpty()) return false
        val viewer = root.findAccessibilityNodeInfosByViewId(Prefs.CLIPS_VIEWER_ID).firstOrNull {
            it.isVisibleToUser && Rect().also(it::getBoundsInScreen).width() > screen.width() / 2
        }
            ?: return false
        val area = Rect().also(viewer::getBoundsInScreen)
        if (labels.none { area.contains(it.centerX(), it.centerY()) }) return false
        lastAdSkipAt = now
        if (!viewer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            swipe(screen.width() / 2f, screen.height() * 0.75f, screen.width() / 2f, screen.height() * 0.25f)
        }
        setStatus("Pulou um anúncio no Reels")
        return true
    }

    // findAccessibilityNodeInfosByText procura por trecho; só vale o rótulo exato,
    // para não confundir com uma legenda que contenha a palavra.
    private fun adLabelBounds(root: AccessibilityNodeInfo): List<Rect> =
        config.adLabels.flatMap { label ->
            root.findAccessibilityNodeInfosByText(label).filter { node ->
                node.isVisibleToUser && listOf(node.text, node.contentDescription).any {
                    it?.toString()?.trim()?.equals(label, ignoreCase = true) == true
                }
            }
        }.map { Rect().also(it::getBoundsInScreen) }

    // Ponto inicial igual ao final vira um toque.
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val duration = if (x1 == x2 && y1 == y2) 50L else 200L
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            null,
            null,
        )
    }

    private fun isInsideMainPager(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        repeat(40) {
            val c = current ?: return false
            if (c.viewIdResourceName == Prefs.MAIN_PAGER_ID) return true
            current = c.parent
        }
        return false
    }

    private fun hideOverlay() {
        overlay.hide()
        current = null
        keptTabs = emptyList()
    }

    private fun onTabTap(index: Int) = clickTab(index, AccessibilityNodeInfo.ACTION_CLICK)

    // Toque longo no perfil abre a troca de conta, como no app original.
    private fun onTabLongPress(index: Int) = clickTab(index, AccessibilityNodeInfo.ACTION_LONG_CLICK)

    private fun clickTab(index: Int, action: Int) {
        val node = keptTabs.getOrNull(index) ?: return
        node.refresh()
        node.performAction(action)
        requestCapture()
        schedule()
    }

    // Janela mais alta sob o centro da barra: se não for o Instagram (aba de notificações,
    // teclado, outro app em tela dividida), o overlay não pode ficar por cima.
    private fun topWindowAt(windows: List<AccessibilityWindowInfo>, r: Rect): AccessibilityWindowInfo? {
        val tmp = Rect()
        return windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .filter { it.getBoundsInScreen(tmp); tmp.contains(r.centerX(), r.centerY()) }
            .maxByOrNull { it.layer }
    }

    // Captura agora e de novo no meio e no fim da animação de troca de aba do Instagram.
    private fun requestCapture() {
        captureWanted = true
        for (delay in SETTLE_STEPS_MS) {
            handler.postDelayed({
                captureWanted = true
                schedule(0)
            }, delay)
        }
    }

    private fun maybeCapture() {
        val snapshot = current ?: return
        if (!captureWanted || capturing) return
        val wait = lastShotAt + MIN_SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            if (!captureRetryPosted) {
                captureRetryPosted = true
                handler.postDelayed({
                    captureRetryPosted = false
                    schedule(0)
                }, wait)
            }
            return
        }
        captureWanted = false
        capturing = true
        lastShotAt = SystemClock.uptimeMillis()

        // Captura só a janela do Instagram, então o próprio overlay não aparece na imagem.
        takeScreenshotOfWindow(snapshot.windowId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                capturing = false
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                if (bitmap == null) return
                applyCapture(bitmap, snapshot)
                bitmap.recycle()
            }

            override fun onFailure(errorCode: Int) {
                capturing = false
            }
        })
    }

    private fun applyCapture(bitmap: Bitmap, snapshot: Snapshot) {
        val wb = snapshot.windowBounds
        val sx = bitmap.width.toFloat() / wb.width()
        val sy = bitmap.height.toFloat() / wb.height()
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        fun toBitmap(r: Rect) = Rect(
            ((r.left - wb.left) * sx).toInt(),
            ((r.top - wb.top) * sy).toInt(),
            ((r.right - wb.left) * sx).toInt(),
            ((r.bottom - wb.top) * sy).toInt(),
        ).takeIf { it.intersect(full) && !it.isEmpty }

        val bar = toBitmap(snapshot.barBounds) ?: return
        // Canto da barra: fora de qualquer ícone ou da pílula da aba selecionada.
        val color = bitmap.getPixel(bar.left + 2, bar.top + 3)

        val tabs = snapshot.tabBounds.map { r ->
            val src = toBitmap(r) ?: return
            TabImage(Bitmap.createBitmap(bitmap, src.left, src.top, src.width(), src.height()), r.width(), r.height())
        }
        barColor = color
        overlay.setContent(color, tabs)
    }

    private fun fallbackColor(): Int {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
    }

    private fun maybeDump(root: AccessibilityNodeInfo) {
        if (!prefs.getBoolean(Prefs.DUMP_REQUESTED, false)) return
        prefs.edit().putBoolean(Prefs.DUMP_REQUESTED, false).apply()
        val dir = getExternalFilesDir("dumps") ?: File(filesDir, "dumps").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "instagram-$stamp.txt")
        file.writeText(TreeDumper.dump(root))
        Toast.makeText(this, "Diagnóstico salvo: ${file.name}", Toast.LENGTH_LONG).show()
    }

    private fun setStatus(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        prefs.edit().putString(Prefs.LAST_STATUS, status).apply()
    }

    private companion object {
        // takeScreenshot recusa chamadas muito próximas (limite do sistema ~333 ms).
        const val MIN_SHOT_INTERVAL_MS = 340L
        val SETTLE_STEPS_MS = longArrayOf(250L, 600L)
        const val REDIRECT_COOLDOWN_MS = 800L
        const val UNMUTE_DELAY_MS = 400L
        const val AD_COOLDOWN_MS = 700L
        const val STORY_POLL_MS = 150L
        const val ACTION_DUMP = "dev.semreels.DUMP"
    }
}
