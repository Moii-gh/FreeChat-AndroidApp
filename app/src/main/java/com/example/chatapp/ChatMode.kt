package com.example.chatapp

object ChatMode {
    const val CREATE_IMAGE = "create_image"
    const val SEARCH = "search"
    const val SHOPPING = "shopping"
    const val STUDY = "study"

    fun usesFreshWebContext(mode: String?): Boolean =
        mode == SEARCH || mode == SHOPPING
}
