package com.example.privatespace

import com.example.privatespace.core.Aead
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class Note(val id: Long, var title: String, var text: String, var time: Long)

class FileEntry(val id: String, val name: String, val size: Long, val mime: String, val time: Long)

class SpaceSettings {
    var lockOnLeave = true
    var shakeLock = true
    var deleteOriginal = false
}

/**
 * Everything inside one space (notes, file index, settings) as one encrypted file. The plaintext is padded with
 * random bytes to a multiple of 64 KB so the file size only hints at the amount of data in coarse steps.
 */
class SpaceStore(private val file: File, private val key: ByteArray) {
    val notes = ArrayList<Note>()
    val files = ArrayList<FileEntry>()
    val settings = SpaceSettings()

    fun load() {
        if (!file.exists()) return
        val plain = Aead.open(key, file.readBytes()) ?: return
        if (plain.size < 4) return
        val len = ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
            ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
        if (len < 0 || len > plain.size - 4) return
        try {
            val o = JSONObject(String(plain, 4, len, Charsets.UTF_8))
            notes.clear()
            files.clear()
            val na = o.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until na.length()) {
                val n = na.getJSONObject(i)
                notes.add(Note(n.getLong("id"), n.optString("t"), n.optString("x"), n.optLong("time")))
            }
            val fa = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                files.add(FileEntry(f.getString("id"), f.optString("n"), f.optLong("s"), f.optString("m"), f.optLong("time")))
            }
            val s = o.optJSONObject("settings")
            if (s != null) {
                settings.lockOnLeave = s.optBoolean("leave", true)
                settings.shakeLock = s.optBoolean("shake", true)
                settings.deleteOriginal = s.optBoolean("del", false)
            }
        } catch (e: Exception) {
            // unreadable content: start empty rather than crash
        }
    }

    fun save() {
        val o = JSONObject()
        val na = JSONArray()
        for (n in notes) na.put(JSONObject().put("id", n.id).put("t", n.title).put("x", n.text).put("time", n.time))
        val fa = JSONArray()
        for (f in files) fa.put(JSONObject().put("id", f.id).put("n", f.name).put("s", f.size).put("m", f.mime).put("time", f.time))
        o.put("notes", na).put("files", fa)
        o.put("settings", JSONObject().put("leave", settings.lockOnLeave).put("shake", settings.shakeLock).put("del", settings.deleteOriginal))
        val json = o.toString().toByteArray(Charsets.UTF_8)
        val padded = ((4 + json.size + BUCKET - 1) / BUCKET).coerceAtLeast(1) * BUCKET
        val buf = ByteArray(padded).also { SecureRandom().nextBytes(it) }
        buf[0] = (json.size ushr 24).toByte()
        buf[1] = (json.size ushr 16).toByte()
        buf[2] = (json.size ushr 8).toByte()
        buf[3] = json.size.toByte()
        System.arraycopy(json, 0, buf, 4, json.size)
        writeAtomic(file, Aead.seal(key, buf))
    }

    private companion object {
        const val BUCKET = 64 * 1024
    }
}

internal fun writeAtomic(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
