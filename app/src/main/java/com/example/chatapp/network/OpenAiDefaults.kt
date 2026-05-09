package com.example.chatapp.network

internal object OpenAiDefaults {
    const val BASE_URL = "https://api.openai.com/v1/"
    const val CHAT_MODEL = "gpt-5.4-mini"
    const val FILE_SEARCH_MAX_RESULTS = 20
    const val FILE_SEARCH_VECTOR_STORE_TTL_DAYS = 1
    const val FILE_SEARCH_POLL_INTERVAL_MS = 500L
    const val FILE_SEARCH_MAX_POLL_ATTEMPTS = 60
}
