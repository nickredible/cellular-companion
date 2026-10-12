package com.xuanhan.cellularcompanion.utilities

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

class AES(password: String) {
    private var key: SecretKeySpec? = null
    private val cipher = Cipher.getInstance("AES/CBC/PKCS7PADDING")

    init {
        key = SecretKeySpec(password.toByteArray(), "AES")
    }

    fun encrypt(plaintext: String): Pair<String, String> {
        if (key == null) {
            throw UnsupportedOperationException("Initialise AES class before calling encrypt()!")
        }

        val plainText = plaintext.toByteArray(Charsets.UTF_8)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val cipherText = cipher.doFinal(plainText)
        val iv = cipher.iv
        // NO_WRAP: the default flags insert line breaks, which older versions of the Mac app can't decode
        return Pair(Base64.encodeToString(cipherText, Base64.NO_WRAP), iv.toHexString())
    }
}