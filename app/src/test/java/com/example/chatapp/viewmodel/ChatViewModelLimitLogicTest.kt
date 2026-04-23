package com.example.chatapp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelLimitLogicTest {

    @Test
    fun `next day keeps rewarded requests when balance is above daily limit`() {
        assertEquals(27, nextDayRequestsLeft(27))
    }

    @Test
    fun `next day restores base daily limit when balance is below it`() {
        assertEquals(DAILY_REQUEST_LIMIT, nextDayRequestsLeft(6))
    }
}
