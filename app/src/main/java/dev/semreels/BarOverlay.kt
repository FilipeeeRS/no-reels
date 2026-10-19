package dev.semreels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Recorte de uma aba real do Instagram e o tamanho que ela ocupa na tela. */
class TabImage(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * Cobre a barra inteira do Instagram e redesenha só as abas mantidas, igualmente
 * espaçadas. Os toques viram cliques nas abas reais via [onTap] e [onLongPress].
 */
class BarOverlay(
    context: Context,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = BarView(context, onTap, onLongPress)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    val isShowing get() = attached

    fun show(bounds: Rect, slots: Int) {
        view.slots = slots
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

    fun setBackground(color: Int) = view.setContent(color, null)

    fun setContent(color: Int, tabs: List<TabImage>) = view.setContent(color, tabs)

    fun hide() {
        if (!attached) return
        wm.removeView(view)
        attached = false
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class BarView(
    context: Context,
    private val onTap: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit,
) : View(context) {

    var slots = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var color = Color.BLACK
    private var tabs = emptyList<TabImage>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            slotAt(e.x)?.let(onTap)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            slotAt(e.x)?.let(onLongPress)
        }
    })

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setContent(newColor: Int, newTabs: List<TabImage>?) {
        color = newColor
        if (newTabs != null) {
            val old = tabs
            tabs = newTabs
            old.forEach { it.bitmap.recycle() }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(color)
        // Enquanto a captura não bate com as abas atuais, mostra só o fundo.
        if (slots == 0 || tabs.size != slots) return
        val slotWidth = width.toFloat() / slots
        tabs.forEachIndexed { i, tab ->
            val cx = (slotWidth * (i + 0.5f)).toInt()
            val cy = height / 2
            dst.set(cx - tab.width / 2, cy - tab.height / 2, cx - tab.width / 2 + tab.width, cy - tab.height / 2 + tab.height)
            canvas.drawBitmap(tab.bitmap, null, dst, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    private fun slotAt(x: Float): Int? =
        if (slots == 0) null else (x / (width.toFloat() / slots)).toInt().coerceIn(0, slots - 1)
}
