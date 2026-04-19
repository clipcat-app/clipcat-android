package com.clipcat.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcmCrypto {
    const val NONCE_SIZE = 12

    fun encrypt(plain: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcm = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcm)

        val encryptedWithTag = cipher.doFinal(plain)
        return nonce to encryptedWithTag
    }

    fun decrypt(encryptedWithTag: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcm = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcm)
        return cipher.doFinal(encryptedWithTag)
    }
}
