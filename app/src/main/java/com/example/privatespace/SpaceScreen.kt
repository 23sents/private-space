package com.example.privatespace

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.privatespace.core.Pin
import java.text.DateFormat
import java.util.Date

/**
 * One unlocked space: notes, files and settings. The real space and the decoy space use exactly the same
 * screen; the decoy simply starts empty with default settings.
 */
class SpaceScreen(private val host: MainActivity, private val session: Session) : FrameLayout(host) {
    private val store = session.store
    private val body = FrameLayout(host)
    private lateinit var tabViews: Array<TextView>
    private var overlay: View? = null
    private var tab = 0

    init {
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(
            tabBar(),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(d(14f), d(6f), d(14f), d(12f)) }
        )
        addView(column, LayoutParams(MATCH, MATCH))
        show(0)
    }

    private fun d(v: Float) = context.dp(v)

    /** Back button: closes an open note/image first; true if something was closed. */
    fun handleBack(): Boolean {
        if (overlay == null) return false
        closeOverlay()
        return true
    }

    fun refresh() = show(tab)

    // ------------------------------------------------------------------ tabs
    private fun tabBar(): View {
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.drawable(host, 28f, 0x33FFFFFF)
            setPadding(d(6f), d(6f), d(6f), d(6f))
        }
        val labels = listOf("📝\nЗаметки", "📁\nФайлы", "⚙️\nНастройки")
        tabViews = Array(3) { i ->
            TextView(host).apply {
                text = labels[i]
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, d(6f), 0, d(6f))
                setOnClickListener { show(i) }
            }
        }
        for (t in tabViews) bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun show(i: Int) {
        tab = i
        body.removeAllViews()
        body.addView(
            when (i) {
                0 -> notesPage()
                1 -> filesPage()
                else -> settingsPage()
            },
            LayoutParams(MATCH, MATCH)
        )
        for ((k, t) in tabViews.withIndex()) {
            t.background = if (k == i) Glass.drawable(host, 22f, 0x40FFFFFF) else null
        }
    }

    private class Page(val view: View, val list: LinearLayout)

    private fun page(title: String, actionLabel: String?, action: () -> Unit): Page {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(18f), d(16f), 0)
        }
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 30f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (actionLabel != null) {
            header.addView(round(actionLabel) { action() })
        }
        header.addView(round("🔒") { host.lockNow() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = d(8f) })
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(12f), 0, d(12f)) }
        val scroll = ScrollView(host).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return Page(root, list)
    }

    private fun round(symbol: String, onClick: () -> Unit) = TextView(host).apply {
        text = symbol
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = Glass.circle(host, 0x22FFFFFF)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(d(46f), d(46f))
    }

    // ------------------------------------------------------------------ notes
    private fun notesPage(): View {
        val p = page("Заметки", "＋") { editNote(null) }
        if (store.notes.isEmpty()) p.list.addView(hint("Здесь пока пусто"))
        for (n in store.notes.sortedByDescending { it.time }) {
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.drawable(host, 20f)
                setPadding(d(16f), d(12f), d(16f), d(14f))
                setOnClickListener { editNote(n) }
            }
            card.addView(label(n.title.ifEmpty { "Без названия" }, 17f, bold = true))
            if (n.text.isNotEmpty()) card.addView(label(n.text.take(120), 14f, alpha = 170), lp(4))
            p.list.addView(card, lp(10))
        }
        return p.view
    }

    private fun editNote(existing: Note?) {
        val ov = FrameLayout(host).apply {
            setBackgroundColor(Color.argb(248, 10, 12, 26))
            isClickable = true
        }
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18f), d(24f), d(18f), d(18f))
        }
        val title = field("Заголовок", existing?.title ?: "", multiline = false)
        val text = field("Текст", existing?.text ?: "", multiline = true)
        col.addView(title, lp())
        col.addView(text, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = d(10f) })
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        if (existing != null) {
            row.addView(
                Glass.button(host, "Удалить", 0x33FF453A, 0x66FF453A) {
                    store.notes.remove(existing)
                    store.save()
                    closeOverlay()
                    show(0)
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = d(8f) }
            )
        }
        row.addView(
            Glass.button(host, "Готово", 0x553A7BFF, 0x883A7BFF.toInt()) {
                val t = title.text.toString().trim()
                val b = text.text.toString()
                val now = System.currentTimeMillis()
                if (existing != null) {
                    existing.title = t; existing.text = b; existing.time = now
                } else if (t.isNotEmpty() || b.isNotEmpty()) {
                    store.notes.add(Note(now, t, b, now))
                }
                store.save()
                closeOverlay()
                show(0)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        col.addView(row, lp(12))
        ov.addView(col, LayoutParams(MATCH, MATCH))
        openOverlay(ov)
    }

    // ------------------------------------------------------------------ files
    private fun filesPage(): View {
        val p = page("Файлы", "＋") { host.pickFiles() }
        if (store.files.isEmpty()) p.list.addView(hint("Здесь пока пусто"))
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (f in store.files.sortedByDescending { it.time }) {
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.drawable(host, 20f)
                setPadding(d(16f), d(12f), d(16f), d(14f))
                setOnClickListener { fileActions(f) }
            }
            card.addView(label(f.name, 16f, bold = true))
            card.addView(label("${size(f.size)} · ${fmt.format(Date(f.time))}", 13f, alpha = 160), lp(3))
            p.list.addView(card, lp(10))
        }
        return p.view
    }

    private fun fileActions(f: FileEntry) {
        val isImage = f.mime.startsWith("image/")
        val items = ArrayList<String>()
        if (isImage) items.add("Открыть")
        items.add("Экспортировать…")
        items.add("Удалить")
        AlertDialog.Builder(host)
            .setTitle(f.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Открыть" -> viewImage(f)
                    "Экспортировать…" -> host.exportFile(f)
                    else -> confirm("Удалить файл?", "Он будет стёрт из пространства.", "Удалить") {
                        VaultManager.deleteBlob(f.id)
                        store.files.remove(f)
                        store.save()
                        show(1)
                    }
                }
            }
            .show()
    }

    private fun viewImage(f: FileEntry) {
        host.io.execute {
            val data = VaultManager.readBlob(session, f, 32 * 1024 * 1024)
            val bmp = data?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
                BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            host.runOnUiThread {
                if (bmp == null) {
                    host.toast("Не удалось открыть изображение")
                } else {
                    val ov = FrameLayout(host).apply { setBackgroundColor(Color.argb(250, 4, 5, 12)); isClickable = true }
                    ov.addView(ImageView(host).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER }, LayoutParams(MATCH, MATCH))
                    ov.addView(
                        round("✕") { closeOverlay() },
                        LayoutParams(d(46f), d(46f), Gravity.TOP or Gravity.END).apply { setMargins(0, d(20f), d(16f), 0) }
                    )
                    openOverlay(ov)
                }
            }
        }
    }

    // ------------------------------------------------------------------ settings
    private fun settingsPage(): View {
        val p = page("Настройки", null) { }
        val s = store.settings
        p.list.addView(switchRow("Блокировать при выходе из приложения", s.lockOnLeave) { s.lockOnLeave = it; store.save() }, lp(0))
        p.list.addView(switchRow("Блокировать при встряхивании", s.shakeLock) {
            s.shakeLock = it
            store.save()
            host.updateShake()
        }, lp(10))
        p.list.addView(switchRow("Удалять оригинал после импорта файлов", s.deleteOriginal) { s.deleteOriginal = it; store.save() }, lp(10))
        p.list.addView(
            switchRow("Стереть всё после 10 неверных PIN", VaultManager.wipeAfterTen) { VaultManager.wipeAfterTen = it },
            lp(10)
        )
        p.list.addView(Glass.button(host, "Сменить PIN этого пространства") { changePinDialog() }, lp(18))
        p.list.addView(
            Glass.button(host, "Очистить это пространство", 0x33FF453A, 0x66FF453A) {
                confirm("Очистить пространство?", "Все заметки и файлы здесь будут стёрты.", "Очистить") { clearSpace() }
            },
            lp(10)
        )
        p.list.addView(label("Версия 1.0", 12f, alpha = 110).apply { gravity = Gravity.CENTER }, lp(20))
        return p.view
    }

    private fun clearSpace() {
        for (f in store.files) VaultManager.deleteBlob(f.id)
        store.files.clear()
        store.notes.clear()
        store.save()
        show(tab)
    }

    private fun changePinDialog() {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20f), d(8f), d(20f), 0)
        }
        fun pinField(hintText: String) = EditText(host).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(Pin.MAX))
        }
        val a = pinField("Новый PIN (4–8 цифр)")
        val b = pinField("Повторите PIN")
        box.addView(a)
        box.addView(b)
        AlertDialog.Builder(host)
            .setTitle("Сменить PIN")
            .setView(box)
            .setPositiveButton("Сменить") { _, _ ->
                val pin = a.text.toString()
                when {
                    !Pin.isValid(pin) -> host.toast("PIN: от 4 до 8 цифр")
                    pin != b.text.toString() -> host.toast("PIN не совпадает")
                    else -> host.io.execute {
                        val ok = VaultManager.changePin(session, pin)
                        host.runOnUiThread { host.toast(if (ok) "PIN изменён" else "Этот PIN занят, выберите другой") }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers
    private fun openOverlay(v: View) {
        closeOverlay()
        addView(v, LayoutParams(MATCH, MATCH))
        overlay = v
    }

    private fun closeOverlay() {
        overlay?.let { removeView(it) }
        overlay = null
    }

    private fun confirm(title: String, message: String, positive: String, onYes: () -> Unit) {
        AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(host).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = checked
        background = Glass.drawable(host, 22f)
        setPadding(d(16f), d(14f), d(16f), d(14f))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun field(hintText: String, value: String, multiline: Boolean): EditText = EditText(host).apply {
        hint = hintText
        setText(value)
        setHintTextColor(Color.argb(110, 255, 255, 255))
        setTextColor(Color.WHITE)
        textSize = if (multiline) 16f else 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = Glass.drawable(host, 18f)
        setPadding(d(16f), d(12f), d(16f), d(12f))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        if (multiline) gravity = Gravity.TOP or Gravity.START
    }

    private fun label(text: String, sp: Float, alpha: Int = 255, bold: Boolean = false): TextView = TextView(host).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.argb(alpha, 255, 255, 255))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun hint(text: String) = label(text, 15f, alpha = 130).apply {
        gravity = Gravity.CENTER
        setPadding(0, d(48f), 0, 0)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(top.toFloat()) }

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> "%.1f МБ".format(bytes / 1048576.0)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
