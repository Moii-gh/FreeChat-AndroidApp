package com.example.chatapp.network

import com.example.chatapp.network.dto.ChatShareSnapshotResponse
import com.example.chatapp.network.dto.CreateChatShareRequest
import com.example.chatapp.network.dto.CreateChatShareResponse
import com.example.chatapp.network.dto.RevokeChatShareResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatShareApiService {
    @POST("chat-shares")
    suspend fun createChatShare(
        @Body request: CreateChatShareRequest
    ): Response<CreateChatShareResponse>

    @GET("chat-shares/{token}")
    suspend fun getChatShare(
        @Path("token") token: String
    ): Response<ChatShareSnapshotResponse>

    @DELETE("chat-shares/{token}")
    suspend fun revokeChatShare(
        @Path("token") token: String
    ): Response<RevokeChatShareResponse>

    @POST("chat-shares/chats/{chatId}/revoke")
    suspend fun revokeChatSharesForChat(
        @Path("chatId") chatId: String
    ): Response<RevokeChatShareResponse>
}
