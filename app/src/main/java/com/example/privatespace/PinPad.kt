package com.example.privatespace

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.privatespace.core.Pin

/**
 * A private keypad drawn by the app (the system keyboard never sees the PIN). It shows no hint about the PIN
 * length: only the digits typed so far.
 */
class PinPad(ctx: Context, private val onSubmit: (String) -> Unit) : LinearLayout(ctx) {
    private val digits = StringBuilder()
    private val dots = DotsView(ctx)
    private val prompt = TextView(ctx)

    /** While true (checking a PIN) key presses are ignored. */
    var busy = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        prompt.setTextColor(Color.argb(210, 255, 255, 255))
        prompt.textSize = 15f
        prompt.gravity = Gravity.CENTER
        prompt.setPadding(ctx.dp(28f), 0, ctx.dp(28f), ctx.dp(14f))
        prompt.visibility = GONE
        addView(prompt, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(44f)))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        for (r in rows) {
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for (k in r) {
                row.addView(key(k), LayoutParams(ctx.dp(76f), ctx.dp(76f)).apply { setMargins(ctx.dp(9f), ctx.dp(8f), ctx.dp(9f), ctx.dp(8f)) })
            }
            addView(row)
        }
    }

    fun setPrompt(text: String?) {
        prompt.text = text ?: ""
        prompt.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun reset() {
        digits.setLength(0)
        dots.count = 0
        busy = false
    }

    fun shake() {
        ObjectAnimator.ofFloat(this, "translationX", 0f, 26f, -26f, 18f, -18f, 8f, 0f).setDuration(380).start()
    }

    private fun key(label: String): TextView {
        val tv = TextView(context)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = if (label.length == 1 && label[0].isDigit()) 28f else 20f
        tv.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        tv.gravity = Gravity.CENTER
        tv.background = Glass.circle(context, if (label.length == 1 && label[0].isDigit()) 0x22FFFFFF else 0x10FFFFFF)
        tv.isClickable = true
        tv.setOnClickListener {
            if (!busy) press(label)
        }
        return tv
    }

    private fun press(k: String) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (k) {
            "⌫" -> if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
            "OK" -> {
                if (digits.length >= Pin.MIN) {
                    busy = true
                    onSubmit(digits.toString())
                }
                return
            }
            else -> if (digits.length < Pin.MAX) digits.append(k)
        }
        dots.count = digits.length
    }
}

private class DotsView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var count = 0
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        if (count == 0) return
        val d = resources.displayMetrics.density
        val r = 6.5f * d
        val gap = 18f * d
        val total = count * 2 * r + (count - 1) * gap
        var x = (width - total) / 2f + r
        for (i in 0 until count) {
            c.drawCircle(x, height / 2f, r, paint)
            x += 2 * r + gap
        }
    }
}
