package com.example.privatespace.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a random 96-bit nonce: output = iv(12) || ciphertext || tag(16). */
object Aead {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    const val OVERHEAD = IV_LEN + TAG_BITS / 8

    fun seal(key: ByteArray, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + c.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < OVERHEAD) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_LEN)))
            c.doFinal(data, IV_LEN, data.size - IV_LEN)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
