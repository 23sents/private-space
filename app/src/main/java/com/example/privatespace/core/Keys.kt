package com.example.privatespace.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Keys {
    /** Every stored file gets its own key, so a random nonce prefix can never collide across files. */
    fun fileKey(spaceKey: ByteArray, fileId: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(spaceKey, "HmacSHA256"))
        mac.update("file".toByteArray(Charsets.UTF_8))
        return mac.doFinal(fileId)
    }
}
