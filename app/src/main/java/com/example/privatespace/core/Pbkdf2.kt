package com.example.privatespace.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * PBKDF2-HMAC-SHA256 implemented on top of Mac: the JDK/Android "PBKDF2WithHmacSHA256" factory
 * only exists from API 26, this works everywhere. Verified against RFC 7914 test vectors.
 */
object Pbkdf2 {
    fun sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(password.isNotEmpty() && iterations >= 1 && dkLen >= 1)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (i - 1) * hLen
            System.arraycopy(t, 0, out, off, min(hLen, dkLen - off))
        }
        return out
    }
}
