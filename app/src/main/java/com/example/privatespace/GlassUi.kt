package com.example.privatespace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

object Glass {
    fun drawable(ctx: Context, radiusDp: Float, fill: Int = 0x1FFFFFFF, stroke: Int = 0x59FFFFFF): Drawable {
        val r = ctx.dp(radiusDp).toFloat()
        val base = GradientDrawable().apply {
            cornerRadius = r
            setColor(fill)
            setStroke(ctx.dp(1f).coerceAtLeast(1), stroke)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x45FFFFFF, 0x00FFFFFF, 0x12FFFFFF))
            .apply { cornerRadius = r }
        return LayerDrawable(arrayOf<Drawable>(base, sheen))
    }

    /** Round glass key. */
    fun circle(ctx: Context, fill: Int): Drawable {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(ctx.dp(1f).coerceAtLeast(1), 0x66FFFFFF)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x50FFFFFF, 0x00FFFFFF, 0x14FFFFFF))
            .apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf<Drawable>(base, sheen))
    }

    fun button(ctx: Context, text: String, fill: Int = 0x24FFFFFF, pressed: Int = 0x40FFFFFF, onClick: () -> Unit): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.gravity = Gravity.CENTER
        tv.setPadding(ctx.dp(16f), ctx.dp(14f), ctx.dp(16f), ctx.dp(14f))
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), drawable(ctx, 24f, pressed))
        states.addState(intArrayOf(), drawable(ctx, 24f, fill))
        tv.background = states
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }
}

class BackgroundView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(8, 10, 24), Color.rgb(22, 14, 46)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        blob(c, w * 0.10f, h * 0.14f, w * 0.75f, Color.argb(150, 70, 90, 255))
        blob(c, w * 0.95f, h * 0.50f, w * 0.85f, Color.argb(130, 140, 70, 250))
        blob(c, w * 0.20f, h * 0.92f, w * 0.80f, Color.argb(110, 40, 160, 220))
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.shader = RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }
}
