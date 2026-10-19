package dev.semreels

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Localiza a barra inferior do Instagram e classifica cada aba como mantida ou escondida.
 * A barra precisa estar na parte de baixo da tela e ter largura de barra; isso evita
 * cobrir outra coisa caso o Instagram reaproveite um ID.
 */
class BarFinder {

    class Tab(val node: AccessibilityNodeInfo, val bounds: Rect, val hidden: Boolean)
    class Bar(val bounds: Rect, val tabs: List<Tab>)

    fun find(root: AccessibilityNodeInfo, cfg: BarConfig, screen: Rect): Bar? {
        val barNode = byId(root, cfg, screen)
            ?: findKnownTab(root, cfg)?.parent?.takeIf { plausible(boundsOf(it), screen) }
            ?: return null
        val tabs = (0 until barNode.childCount)
            .mapNotNull(barNode::getChild)
            .filter { it.isVisibleToUser && !boundsOf(it).isEmpty }
            .map { Tab(it, boundsOf(it), cfg.isHidden(it)) }
        if (tabs.isEmpty()) return null
        return Bar(boundsOf(barNode), tabs)
    }

    private fun byId(root: AccessibilityNodeInfo, cfg: BarConfig, screen: Rect): AccessibilityNodeInfo? {
        for (id in cfg.tabBarIds) {
            for (node in root.findAccessibilityNodeInfosByViewId(id)) {
                if (node.isVisibleToUser && plausible(boundsOf(node), screen)) return node
            }
        }
        return null
    }

    // Plano B: a barra mudou de ID, mas a aba do Reels ou da Pesquisa ainda é reconhecível.
    private fun findKnownTab(root: AccessibilityNodeInfo, cfg: BarConfig): AccessibilityNodeInfo? {
        for (id in cfg.reels.ids + cfg.search.ids) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (cfg.reels.matches(node) || cfg.search.matches(node))) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun plausible(r: Rect, screen: Rect): Boolean =
        !r.isEmpty &&
            r.top >= screen.top + screen.height() * 0.6f &&
            r.bottom <= screen.bottom + 1 &&
            r.width() >= screen.width() / 2 &&
            r.height() <= screen.height() / 5

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private companion object {
        const val MAX_NODES = 3000
    }
}
