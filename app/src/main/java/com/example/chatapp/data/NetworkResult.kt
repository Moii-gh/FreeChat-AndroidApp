package com.example.chatapp.data

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>

    data class Error(
        val message: String,
        val fieldErrors: Map<String, String> = emptyMap()
    ) : NetworkResult<Nothing>
}
