package com.clipcat.app.network

import com.clipcat.app.crypto.AesGcmCrypto
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object TcpImageSender {
    fun send(
        ip: String,
        port: Int,
        key: ByteArray,
        imageBytes: ByteArray,
        timeoutMs: Int = 3000,
        onProgress: ((Int) -> Unit)? = null
    ) {
        val (nonce, encrypted) = AesGcmCrypto.encrypt(imageBytes, key)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            DataOutputStream(socket.getOutputStream()).use { output ->
                output.writeInt(encrypted.size)
                output.write(nonce)
                var sent = 0
                val chunkSize = 16 * 1024
                while (sent < encrypted.size) {
                    val remaining = encrypted.size - sent
                    val currentChunk = minOf(chunkSize, remaining)
                    output.write(encrypted, sent, currentChunk)
                    sent += currentChunk
                    val percent = (sent * 100f / encrypted.size).toInt().coerceIn(0, 100)
                    onProgress?.invoke(percent)
                }
                output.flush()
            }
        }
    }
}
