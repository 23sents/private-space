package com.example.privatespace.core

import java.security.SecureRandom

/** The extra, device-bound layer (Android Keystore in the app, a plain AES key in tests). */
interface Wrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(data: ByteArray): ByteArray?
}

/** A space that was opened with a PIN. */
class Opened(val slot: Int, val spaceKey: ByteArray, val fileId: ByteArray)

/**
 * The container: salt(16) followed by [SLOTS] fixed-size slots. A slot holds a space key and the id of the
 * space's data file, encrypted first with a key stretched from the PIN (PBKDF2) and then with the device-bound
 * wrapper. Unused slots are random bytes of the same length, so the container does not reveal how many spaces
 * exist, and a PIN simply opens whichever slot it fits. Without this device's keystore the slots cannot even be
 * attacked offline, which makes short PINs much harder to brute-force.
 */
class Vault(
    private val wrapper: Wrapper,
    private val iterations: Int = 120_000,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val SLOTS = 4
        const val SALT_LEN = 16
        private const val KEY_LEN = 32
        private const val ID_LEN = 8
    }

    private val slotLen: Int by lazy { wrapper.wrap(ByteArray(Aead.OVERHEAD + KEY_LEN + ID_LEN)).size }

    /** Builds a new container with one space per PIN (PINs must be distinct); returns it with the opened spaces. */
    fun create(pins: List<String>): Pair<ByteArray, List<Opened>> {
        require(pins.size in 1..SLOTS && pins.toSet().size == pins.size)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val slots = Array(SLOTS) { ByteArray(slotLen).also { b -> random.nextBytes(b) } }
        val order = MutableList(SLOTS) { it }
        for (i in SLOTS - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        val opened = ArrayList<Opened>()
        for ((n, pin) in pins.withIndex()) {
            val key = ByteArray(KEY_LEN).also { random.nextBytes(it) }
            val id = ByteArray(ID_LEN).also { random.nextBytes(it) }
            slots[order[n]] = makeSlot(salt, pin, key, id)
            opened.add(Opened(order[n], key, id))
        }
        return Pair(assemble(salt, slots), opened)
    }

    fun open(container: ByteArray, pin: String): Opened? {
        if (container.size != SALT_LEN + SLOTS * slotLen) return null
        val salt = container.copyOfRange(0, SALT_LEN)
        val derived = derive(pin, salt)
        for (i in 0 until SLOTS) {
            val start = SALT_LEN + i * slotLen
            val outer = wrapper.unwrap(container.copyOfRange(start, start + slotLen)) ?: continue
            val inner = Aead.open(derived, outer) ?: continue
            if (inner.size != KEY_LEN + ID_LEN) continue
            return Opened(i, inner.copyOfRange(0, KEY_LEN), inner.copyOfRange(KEY_LEN, KEY_LEN + ID_LEN))
        }
        return null
    }

    /** True if [pin] already opens a slot other than [exceptSlot]. */
    fun pinInUse(container: ByteArray, pin: String, exceptSlot: Int): Boolean {
        val o = open(container, pin) ?: return false
        return o.slot != exceptSlot
    }

    /** Re-encrypts one slot under a new PIN (same space key and data file). */
    fun rewrite(container: ByteArray, space: Opened, newPin: String): ByteArray {
        val salt = container.copyOfRange(0, SALT_LEN)
        val out = container.copyOf()
        val slot = makeSlot(salt, newPin, space.spaceKey, space.fileId)
        System.arraycopy(slot, 0, out, SALT_LEN + space.slot * slotLen, slotLen)
        return out
    }

    private fun makeSlot(salt: ByteArray, pin: String, key: ByteArray, id: ByteArray): ByteArray {
        val inner = Aead.seal(derive(pin, salt), key + id, random)
        val outer = wrapper.wrap(inner)
        check(outer.size == slotLen)
        return outer
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        Pbkdf2.sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, 32)

    private fun assemble(salt: ByteArray, slots: Array<ByteArray>): ByteArray {
        val out = ByteArray(SALT_LEN + SLOTS * slotLen)
        System.arraycopy(salt, 0, out, 0, SALT_LEN)
        for (i in 0 until SLOTS) System.arraycopy(slots[i], 0, out, SALT_LEN + i * slotLen, slotLen)
        return out
    }
}

object Pin {
    const val MIN = 4
    const val MAX = 8

    fun isValid(pin: String): Boolean = pin.length in MIN..MAX && pin.all { it in '0'..'9' }
}
