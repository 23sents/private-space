package com.example.privatespace

import android.content.Context
import android.content.SharedPreferences
import com.example.privatespace.core.ChunkCipher
import com.example.privatespace.core.Keys
import com.example.privatespace.core.Opened
import com.example.privatespace.core.Pin
import com.example.privatespace.core.Vault
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import kotlin.math.max

/** An unlocked space: its keys (kept only in memory) and its decrypted contents. */
class Session(val opened: Opened, val store: SpaceStore) {
    fun wipeKey() = Arrays.fill(opened.spaceKey, 0)
}

/**
 * Owns the files in the app-private directory (not reachable over USB/MTP or by other apps):
 *   c            the container (salt + 4 slots)
 *   s_<id>       one encrypted file per space
 *   b_<id>       one encrypted blob per imported file
 */
object VaultManager {
    private const val ITERATIONS = 120_000

    private lateinit var dir: File
    private lateinit var meta: SharedPreferences
    private val vault by lazy { Vault(KeystoreWrapper(), ITERATIONS) }

    @Synchronized fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "v").apply { mkdirs() }
        meta = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    }

    private fun containerFile() = File(dir, "c")
    private fun spaceFile(id: ByteArray) = File(dir, "s_" + hex(id))
    private fun blobFile(hexId: String) = File(dir, "b_$hexId")

    fun isSetUp(): Boolean = containerFile().exists()

    /** Creates the container with a real and a decoy space (both start empty and look identical). */
    @Synchronized fun setup(real: String, decoy: String) {
        require(Pin.isValid(real) && Pin.isValid(decoy) && real != decoy)
        val (container, spaces) = vault.create(listOf(real, decoy))
        writeAtomic(containerFile(), container)
        for (s in spaces) SpaceStore(spaceFile(s.fileId), s.spaceKey).save()
        registerSuccess()
    }

    @Synchronized fun unlock(pin: String): Session? {
        val f = containerFile()
        if (!f.exists()) return null
        val o = vault.open(f.readBytes(), pin) ?: return null
        val store = SpaceStore(spaceFile(o.fileId), o.spaceKey)
        store.load()
        return Session(o, store)
    }

    /** false if the new PIN already opens a different space. */
    @Synchronized fun changePin(session: Session, newPin: String): Boolean {
        val container = containerFile().readBytes()
        if (vault.pinInUse(container, newPin, session.opened.slot)) return false
        writeAtomic(containerFile(), vault.rewrite(container, session.opened, newPin))
        return true
    }

    @Synchronized fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        meta.edit().clear().apply()
    }

    // ------------------------------------------------------------------ blobs
    fun importBlob(session: Session, input: InputStream): Pair<String, Long> {
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hexId = hex(id)
        val key = Keys.fileKey(session.opened.spaceKey, id)
        val total = FileOutputStream(blobFile(hexId)).use { fos ->
            ChunkCipher.encrypt(key, BufferedInputStream(input), BufferedOutputStream(fos))
        }
        return Pair(hexId, total)
    }

    fun exportBlob(session: Session, entry: FileEntry, out: OutputStream): Boolean {
        val f = blobFile(entry.id)
        if (!f.exists()) return false
        val key = Keys.fileKey(session.opened.spaceKey, unhex(entry.id))
        return FileInputStream(f).use { ChunkCipher.decrypt(key, BufferedInputStream(it), out) }
    }

    /** Decrypts a (small) blob into memory, e.g. to show an image; null if too large or damaged. */
    fun readBlob(session: Session, entry: FileEntry, maxBytes: Int): ByteArray? {
        val bos = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (size() + len > maxBytes) throw IOException("too large")
                super.write(b, off, len)
            }
        }
        return try {
            if (exportBlob(session, entry, bos)) bos.toByteArray() else null
        } catch (e: IOException) {
            null
        }
    }

    fun deleteBlob(hexId: String) {
        blobFile(hexId).delete()
    }

    // ------------------------------------------------------------------ brute-force protection
    fun lockedForMs(): Long = max(0L, meta.getLong("until", 0L) - System.currentTimeMillis())

    /** Records a wrong PIN; returns the number of consecutive failures. */
    fun registerFailure(): Int {
        val n = meta.getInt("fails", 0) + 1
        val delay = when {
            n >= 7 -> 300_000L
            n == 6 -> 60_000L
            n == 5 -> 30_000L
            else -> 0L
        }
        meta.edit().putInt("fails", n).putLong("until", System.currentTimeMillis() + delay).apply()
        return n
    }

    fun registerSuccess() {
        meta.edit().putInt("fails", 0).putLong("until", 0L).apply()
    }

    var wipeAfterTen: Boolean
        get() = meta.getBoolean("wipe10", false)
        set(v) { meta.edit().putBoolean("wipe10", v).apply() }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
