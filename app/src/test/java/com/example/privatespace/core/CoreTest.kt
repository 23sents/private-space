package com.example.privatespace.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random

class CoreTest {
    private class SoftWrapper(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : Wrapper {
        override fun wrap(plain: ByteArray) = Aead.seal(key, plain)
        override fun unwrap(data: ByteArray) = Aead.open(key, data)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesRfcVectors() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(Pbkdf2.sha256("password".toByteArray(), "salt".toByteArray(), 1, 32))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(Pbkdf2.sha256("password".toByteArray(), "salt".toByteArray(), 2, 32))
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(Pbkdf2.sha256("password".toByteArray(), "salt".toByteArray(), 4096, 32))
        )
    }

    @Test fun aeadRoundTripAndTamper() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = Aead.seal(key, "secret".toByteArray())
        assertArrayEquals("secret".toByteArray(), Aead.open(key, sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(Aead.open(key, sealed))
        assertNull(Aead.open(ByteArray(32), Aead.seal(key, "x".toByteArray())))
    }

    private fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), ChunkCipher.encrypt(key, ByteArrayInputStream(data), out))
        return out.toByteArray()
    }

    @Test fun chunkCipherRoundTripAtBoundaries() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val rnd = Random(1)
        val c = ChunkCipher.CHUNK
        for (size in listOf(0, 1, 100, c - 1, c, c + 1, 2 * c, 3 * c + 17)) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            val enc = encrypt(key, data)
            val out = ByteArrayOutputStream()
            assertTrue("size $size", ChunkCipher.decrypt(key, ByteArrayInputStream(enc), out))
            assertArrayEquals("size $size", data, out.toByteArray())
        }
    }

    @Test fun chunkCipherDetectsTamperTruncationAndWrongKey() {
        val key = ByteArray(32) { it.toByte() }
        val c = ChunkCipher.CHUNK
        val data = ByteArray(3 * c + 5).also { Random(2).nextBytes(it) }
        val enc = encrypt(key, data)

        val flipped = enc.copyOf()
        flipped[enc.size / 2] = (flipped[enc.size / 2].toInt() xor 1).toByte()
        assertFalse(ChunkCipher.decrypt(key, ByteArrayInputStream(flipped), ByteArrayOutputStream()))

        // drop the last chunk entirely (cut at a chunk boundary)
        val cut = 4 + 3 * (4 + c + 16)
        assertFalse(ChunkCipher.decrypt(key, ByteArrayInputStream(enc.copyOf(cut)), ByteArrayOutputStream()))

        assertFalse(ChunkCipher.decrypt(ByteArray(32), ByteArrayInputStream(enc), ByteArrayOutputStream()))
        assertFalse(ChunkCipher.decrypt(key, ByteArrayInputStream(enc + byteArrayOf(1)), ByteArrayOutputStream()))
    }

    @Test fun fileKeysDiffer() {
        val sk = ByteArray(32) { 7 }
        assertFalse(Keys.fileKey(sk, byteArrayOf(1, 2)).contentEquals(Keys.fileKey(sk, byteArrayOf(1, 3))))
    }

    @Test fun vaultOpensTheRightSpaceForEachPin() {
        val vault = Vault(SoftWrapper(), iterations = 500)
        val (container, spaces) = vault.create(listOf("1234", "9876"))
        val real = vault.open(container, "1234")
        val decoy = vault.open(container, "9876")
        assertNotNull(real); assertNotNull(decoy)
        assertEquals(spaces[0].slot, real!!.slot)
        assertEquals(spaces[1].slot, decoy!!.slot)
        assertTrue(real.slot != decoy.slot)
        assertArrayEquals(spaces[0].spaceKey, real.spaceKey)
        assertArrayEquals(spaces[1].fileId, decoy.fileId)
        assertFalse(real.spaceKey.contentEquals(decoy.spaceKey))
        assertNull(vault.open(container, "0000"))
        assertNull(vault.open(container, "12345"))
    }

    @Test fun vaultContainerHasFixedSizeRegardlessOfSpaceCount() {
        val w = SoftWrapper()
        val vault = Vault(w, iterations = 200)
        val one = vault.create(listOf("1111")).first
        val three = vault.create(listOf("1111", "2222", "3333")).first
        assertEquals(one.size, three.size)
        assertEquals(Vault.SALT_LEN + Vault.SLOTS * ((one.size - Vault.SALT_LEN) / Vault.SLOTS), one.size)
    }

    @Test fun vaultForeignDeviceCannotOpen() {
        val container = Vault(SoftWrapper(), iterations = 200).create(listOf("1234")).first
        assertNull(Vault(SoftWrapper(), iterations = 200).open(container, "1234"))
    }

    @Test fun vaultChangePinKeepsTheSpace() {
        val vault = Vault(SoftWrapper(), iterations = 300)
        val (container, spaces) = vault.create(listOf("1234", "5678"))
        val real = spaces[0]
        assertTrue(vault.pinInUse(container, "5678", real.slot))
        assertFalse(vault.pinInUse(container, "1234", real.slot))
        assertFalse(vault.pinInUse(container, "4321", real.slot))
        val changed = vault.rewrite(container, real, "4321")
        val reopened = vault.open(changed, "4321")
        assertNotNull(reopened)
        assertEquals(real.slot, reopened!!.slot)
        assertArrayEquals(real.spaceKey, reopened.spaceKey)
        assertNull(vault.open(changed, "1234"))
        assertNotNull(vault.open(changed, "5678"))
    }

    @Test fun pinPolicy() {
        assertTrue(Pin.isValid("1234"))
        assertTrue(Pin.isValid("12345678"))
        assertFalse(Pin.isValid("123"))
        assertFalse(Pin.isValid("123456789"))
        assertFalse(Pin.isValid("12a4"))
    }
}
