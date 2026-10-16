package com.example.privatespace.core

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM for big files (never holds more than one chunk in memory).
 * Layout: noncePrefix(4) then chunks of [len u32][ciphertext+tag]. Nonce = prefix || chunk counter, and every
 * chunk authenticates a "last chunk" flag, so reordering, dropping or truncating chunks is detected.
 * Use a fresh key per file (see Keys.fileKey).
 */
object ChunkCipher {
    const val CHUNK = 64 * 1024
    private const val TAG = 16

    /** Returns the number of plaintext bytes written. */
    fun encrypt(key: ByteArray, input: InputStream, out: OutputStream, random: SecureRandom = SecureRandom()): Long {
        val prefix = ByteArray(4).also { random.nextBytes(it) }
        out.write(prefix)
        var current = readUpTo(input, CHUNK)
        var counter = 0L
        var total = 0L
        while (true) {
            val next = if (current.size == CHUNK) readUpTo(input, CHUNK) else ByteArray(0)
            val last = next.isEmpty()
            val ct = run(Cipher.ENCRYPT_MODE, key, nonce(prefix, counter), if (last) LAST else MORE, current)
            writeInt(out, ct.size)
            out.write(ct)
            total += current.size
            counter++
            if (last) break
            current = next
        }
        out.flush()
        return total
    }

    /** false if the data was tampered with, truncated or the key is wrong (partial output must be discarded). */
    fun decrypt(key: ByteArray, input: InputStream, out: OutputStream): Boolean {
        val prefix = readExactly(input, 4) ?: return false
        var counter = 0L
        while (true) {
            val len = readInt(input) ?: return false
            if (len < TAG || len > CHUNK + TAG) return false
            val ct = readExactly(input, len) ?: return false
            val nonce = nonce(prefix, counter)
            var last = false
            var plain = tryRun(key, nonce, MORE, ct)
            if (plain == null) {
                plain = tryRun(key, nonce, LAST, ct)
                last = true
            }
            if (plain == null) return false
            if (!last && plain.size != CHUNK) return false
            out.write(plain)
            counter++
            if (last) return input.read() == -1
        }
    }

    private val MORE = byteArrayOf(0)
    private val LAST = byteArrayOf(1)

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 4)
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    private fun run(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun tryRun(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray? =
        try { run(Cipher.DECRYPT_MODE, key, nonce, aad, data) } catch (e: GeneralSecurityException) { null }

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) buf else buf.copyOf(got)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val b = readUpTo(input, n)
        return if (b.size == n) b else null
    }

    private fun readInt(input: InputStream): Int? {
        val b = readExactly(input, 4) ?: return null
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun writeInt(out: OutputStream, v: Int) {
        out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
    }
}
