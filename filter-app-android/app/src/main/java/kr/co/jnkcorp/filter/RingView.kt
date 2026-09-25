package kr.co.jnkcorp.filter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/** 잡티 지우개·브러시 범위를 보여 주는 원: 안쪽 실선 = 크기, 바깥 점선 = 페더(부드러운 가장자리) */
class RingView(ctx: Context) : View(ctx) {
    private var cx = 0f
    private var cy = 0f
    private var inner = 0f
    private var outer = 0f
    private var shown = false

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f
        setShadowLayer(3f, 0f, 0f, 0x99000000.toInt())
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xCCE8743B.toInt(); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // 그림자

    /** 좌표·반지름은 이 뷰(확대 전) 기준. 확대되면 선이 굵어지지 않게 [zoom] 으로 나눔 */
    fun show(x: Float, y: Float, rInner: Float, rOuter: Float, zoom: Float) {
        cx = x; cy = y; inner = rInner; outer = rOuter; shown = true
        solid.strokeWidth = 3f / zoom; dashed.strokeWidth = 2f / zoom
        dashed.pathEffect = DashPathEffect(floatArrayOf(10f / zoom, 8f / zoom), 0f)
        invalidate()
    }

    fun hide() { shown = false; invalidate() }

    override fun onDraw(c: Canvas) {
        if (!shown) return
        if (outer > inner + 0.5f) c.drawCircle(cx, cy, outer, dashed)
        c.drawCircle(cx, cy, inner, solid)
    }
}
