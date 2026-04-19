package com.clipcat.app.model

data class PairingConfig(
    val ip: String,
    val port: Int,
    val keyBase64: String,
    val fastTransfer: Boolean = false
)
