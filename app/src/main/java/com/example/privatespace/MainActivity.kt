package com.example.privatespace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.example.privatespace.core.Pin
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Lock screen -> (setup on first run) -> the space that the entered PIN opens. Nothing on the lock screen tells
 * how many spaces exist. The window is FLAG_SECURE (no screenshots, blank in recents / screen mirroring).
 */
class MainActivity : Activity(), SensorEventListener {
    val io = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private var current: View? = null
    private var pad: PinPad? = null
    private var session: Session? = null
    private var screen: SpaceScreen? = null
    private var external = false
    private var pendingExport: FileEntry? = null
    private var sensors: SensorManager? = null
    private var lastShake = 0L

    // first-run setup state
    private var setupStep = 0
    private var firstPin = ""
    private var realPin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.rgb(8, 10, 24)
        window.navigationBarColor = Color.rgb(22, 14, 46)
        VaultManager.init(this)
        sensors = getSystemService(SENSOR_SERVICE) as? SensorManager
        root = FrameLayout(this)
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        if (VaultManager.isSetUp()) showLock() else showSetup()
    }

    override fun onStop() {
        val s = session
        if (!external && !isChangingConfigurations) {
            if (s == null || s.store.settings.lockOnLeave) {
                lock(false)
                finishAndRemoveTask()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        session?.wipeKey()
        session = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (screen?.handleBack() == true) return
        if (session != null) lockNow() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ screens
    private fun setScreen(v: View) {
        current?.let { root.removeView(it) }
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        current = v
    }

    private fun centered(p: PinPad): View = FrameLayout(this).apply {
        addView(p, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun showLock() {
        val p = PinPad(this) { pin -> tryUnlock(pin) }
        pad = p
        screen = null
        setScreen(centered(p))
        showLockoutIfAny()
    }

    private fun showLockoutIfAny(): Boolean {
        val wait = VaultManager.lockedForMs()
        if (wait > 0) {
            pad?.setPrompt("Подождите ${(wait + 999) / 1000} с")
            return true
        }
        pad?.setPrompt(null)
        return false
    }

    private fun tryUnlock(pin: String) {
        if (showLockoutIfAny()) {
            pad?.reset()
            return
        }
        io.execute {
            val s = VaultManager.unlock(pin)
            runOnUiThread {
                if (s != null) {
                    VaultManager.registerSuccess()
                    showSpace(s)
                } else {
                    onWrongPin()
                }
            }
        }
    }

    private fun onWrongPin() {
        val fails = VaultManager.registerFailure()
        if (VaultManager.wipeAfterTen && fails >= 10) {
            io.execute {
                VaultManager.wipe()
                runOnUiThread { showSetup() }
            }
            return
        }
        pad?.shake()
        pad?.reset()
        showLockoutIfAny()
    }

    private fun showSpace(s: Session) {
        session = s
        val sc = SpaceScreen(this, s)
        screen = sc
        setScreen(sc)
        updateShake()
    }

    /** Wipes the keys from memory and returns to the lock screen. */
    fun lockNow() = lock(true)

    private fun lock(show: Boolean) {
        sensors?.unregisterListener(this)
        session?.wipeKey()
        session = null
        screen = null
        if (show) showLock()
    }

    // ------------------------------------------------------------------ first-run setup
    private fun showSetup() {
        setupStep = 0
        firstPin = ""
        realPin = ""
        val p = PinPad(this) { pin -> onSetupPin(pin) }
        pad = p
        screen = null
        setScreen(centered(p))
        p.setPrompt(SETUP_PROMPTS[0])
    }

    private fun onSetupPin(pin: String) {
        val p = pad ?: return
        when (setupStep) {
            0 -> { firstPin = pin; setupStep = 1 }
            1 -> {
                if (pin != firstPin) return setupRetry("PIN не совпал. Придумайте основной PIN заново.")
                realPin = pin; setupStep = 2
            }
            2 -> {
                if (pin == realPin) return setupRetry("Запасной PIN должен отличаться от основного.", step = 2)
                firstPin = pin; setupStep = 3
            }
            else -> {
                if (pin != firstPin) return setupRetry("PIN не совпал. Придумайте запасной PIN заново.", step = 2)
                io.execute {
                    VaultManager.setup(realPin, pin)
                    runOnUiThread { showLock() }
                }
                return
            }
        }
        p.reset()
        p.setPrompt(SETUP_PROMPTS[setupStep])
    }

    private fun setupRetry(message: String, step: Int = 0) {
        setupStep = step
        pad?.shake()
        pad?.reset()
        pad?.setPrompt(message)
    }

    // ------------------------------------------------------------------ shake to lock
    fun updateShake() {
        sensors?.unregisterListener(this)
        if (session?.store?.settings?.shakeLock == true) {
            sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        if (g > 24f) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShake in 80..800) lockNow()
            lastShake = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ files in / out
    fun pickFiles() {
        external = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    fun exportFile(entry: FileEntry) {
        pendingExport = entry
        external = true
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(entry.mime.ifEmpty { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, entry.name)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        external = false
        val s = session ?: return
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
                data.data?.let { if (uris.isEmpty()) uris.add(it) }
                importAll(s, uris)
            }
            REQ_EXPORT -> {
                val entry = pendingExport ?: return
                val uri = data.data ?: return
                io.execute {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { VaultManager.exportBlob(s, entry, it) } ?: false
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread { toast(if (ok) "Файл экспортирован" else "Не удалось экспортировать") }
                }
            }
        }
    }

    private fun importAll(s: Session, uris: List<Uri>) {
        toast("Шифрую файлы…")
        io.execute {
            val added = ArrayList<FileEntry>()
            var deleted = 0
            for (uri in uris) {
                try {
                    val (name, _) = describe(uri)
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val (id, size) = contentResolver.openInputStream(uri)?.use { VaultManager.importBlob(s, it) } ?: continue
                    added.add(FileEntry(id, name, size, mime, System.currentTimeMillis()))
                    if (s.store.settings.deleteOriginal && removeOriginal(uri)) deleted++
                } catch (e: Exception) {
                    // skip files that cannot be read
                }
            }
            runOnUiThread {
                s.store.files.addAll(added)
                s.store.save()
                screen?.refresh()
                toast(
                    when {
                        added.isEmpty() -> "Ничего не добавлено"
                        s.store.settings.deleteOriginal && deleted < added.size -> "Добавлено: ${added.size}. Оригиналы удалите вручную"
                        else -> "Добавлено: ${added.size}"
                    }
                )
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = "файл"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val z = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (z >= 0) size = c.getLong(z)
            }
        }
        return Pair(name, size)
    }

    private fun removeOriginal(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(contentResolver, uri)
    } catch (e: Exception) {
        false
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_IMPORT = 21
        const val REQ_EXPORT = 22
        val SETUP_PROMPTS = arrayOf(
            "Придумайте основной PIN (4–8 цифр). Он открывает ваше пространство с данными.",
            "Повторите основной PIN",
            "Теперь запасной PIN. Он открывает пустое пространство с базовыми настройками.",
            "Повторите запасной PIN",
        )
    }
}
