package com.example.chatapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenameAnimationPlannerTest {
    @Test
    fun keepsSharedRussianPrefixAndReplacesTail() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "погода в Москве",
            newTitle = "погода в Иваново"
        )

        assertEquals("погода в ", plan.commonPrefix)
        assertEquals("погода в ", plan.deleteSteps.last())
        assertEquals("погода в Иваново", plan.typeSteps.last())
        assertEquals("погода в Иваново", plan.finalText)
    }

    @Test
    fun deletesEverythingWhenThereIsNoCommonPrefix() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "Alpha",
            newTitle = "Beta"
        )

        assertEquals("", plan.commonPrefix)
        assertEquals("", plan.deleteSteps.last())
        assertEquals("B", plan.typeSteps.first())
        assertEquals("Beta", plan.typeSteps.last())
    }

    @Test
    fun doesNothingForIdenticalTitles() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "same title",
            newTitle = "same title"
        )

        assertEquals("same title", plan.commonPrefix)
        assertTrue(plan.deleteSteps.isEmpty())
        assertTrue(plan.typeSteps.isEmpty())
    }

    @Test
    fun preservesSpacesAndShortTitles() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "a b",
            newTitle = "a c"
        )

        assertEquals("a ", plan.commonPrefix)
        assertEquals("a ", plan.deleteSteps.last())
        assertEquals("a c", plan.typeSteps.last())
    }

    @Test
    fun comparesCaseSensitively() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "Chat",
            newTitle = "chat"
        )

        assertEquals("", plan.commonPrefix)
        assertEquals("", plan.deleteSteps.last())
        assertEquals("c", plan.typeSteps.first())
    }

    @Test
    fun handlesSupplementaryUnicodeCharacters() {
        val plan = ChatRenameAnimationPlanner.plan(
            oldTitle = "chat \uD83D\uDE80 old",
            newTitle = "chat \uD83D\uDE80 new"
        )

        assertEquals("chat \uD83D\uDE80 ", plan.commonPrefix)
        assertEquals("chat \uD83D\uDE80 ", plan.deleteSteps.last())
        assertEquals("chat \uD83D\uDE80 new", plan.typeSteps.last())
    }
}
