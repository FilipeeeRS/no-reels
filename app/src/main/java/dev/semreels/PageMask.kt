package dev.semreels

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Retângulo da cor de fundo que cobre o conteúdo enquanto uma aba bloqueada (Reels,
 * Pesquisa) está passando na tela durante o arraste. Não recebe toques, então o gesto
 * continua chegando ao Instagram.
 */
class PageMask(context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var attached = false

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show(bounds: Rect, color: Int) {
        view.setBackgroundColor(color)
        val changed = params.x != bounds.left || params.y != bounds.top ||
            params.width != bounds.width() || params.height != bounds.height()
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        if (!attached) {
            wm.addView(view, params)
            attached = true
        } else if (changed) {
            wm.updateViewLayout(view, params)
        }
    }

    fun hide() {
        if (!attached) return
        wm.removeView(view)
        attached = false
    }
}
