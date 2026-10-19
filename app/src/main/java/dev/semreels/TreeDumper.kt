package dev.semreels

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Gera um texto com a árvore de views, usado para achar novos IDs após uma atualização. */
object TreeDumper {

    fun dump(root: AccessibilityNodeInfo): String = buildString {
        appendLine("pacote=${root.packageName}")
        walk(root, 0, this)
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: StringBuilder) {
        if (node == null || depth > 60) return
        val r = Rect().also(node::getBoundsInScreen)
        out.append("  ".repeat(depth)).append(node.className?.toString()?.substringAfterLast('.'))
        node.viewIdResourceName?.let { out.append(" id=").append(it) }
        node.contentDescription?.let { out.append(" desc=\"").append(it).append('"') }
        node.text?.let { out.append(" texto=\"").append(it.take(40)).append('"') }
        out.append(' ').append(r.toShortString())
        if (node.isClickable) out.append(" clicavel")
        if (node.isSelected) out.append(" selecionado")
        out.appendLine()
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, out)
    }
}
