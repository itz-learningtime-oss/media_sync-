package com.example.network

sealed class TransferResult {
    data class Success(
        val bytesSent: Long,
        val durationMs: Long,
        val message: String
    ) : TransferResult()

    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : TransferResult()
}
