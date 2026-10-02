package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

object TransferSecurityManager {

    private val random = SecureRandom()

    private val _pinCode = MutableStateFlow(generateNewPin())
    val pinCode: StateFlow<String> = _pinCode.asStateFlow()

    private val _authToken = MutableStateFlow(generateNewToken())
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val _isPinRequired = MutableStateFlow(true)
    val isPinRequired: StateFlow<Boolean> = _isPinRequired.asStateFlow()

    private fun generateNewPin(): String {
        val num = 100000 + random.nextInt(900000)
        return num.toString()
    }

    private fun generateNewToken(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    fun regeneratePin(): String {
        val newPin = generateNewPin()
        val newToken = generateNewToken()
        _pinCode.value = newPin
        _authToken.value = newToken
        return newPin
    }

    fun setPinRequired(required: Boolean) {
        _isPinRequired.value = required
    }

    fun validateTokenOrPin(candidate: String?): Boolean {
        if (!_isPinRequired.value) return true
        if (candidate.isNullOrBlank()) return false
        val clean = candidate.trim()
        return clean == _authToken.value || clean == _pinCode.value
    }
}
