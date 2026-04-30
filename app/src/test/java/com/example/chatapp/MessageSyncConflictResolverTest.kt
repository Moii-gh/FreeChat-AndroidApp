package com.example.chatapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSyncConflictResolverTest {

    @Test
    fun higherRemoteRevisionWinsEvenWithOlderTimestamp() {
        assertTrue(
            MessageSyncConflictResolver.shouldAdoptCanonical(
                localEditRevision = 1,
                localUpdatedAt = 5000,
                remoteEditRevision = 2,
                remoteUpdatedAt = 1000,
                snapshotEditRevision = 1
            )
        )
    }

    @Test
    fun lowerRemoteRevisionNeverOverwritesLocalEdit() {
        assertFalse(
            MessageSyncConflictResolver.shouldAdoptCanonical(
                localEditRevision = 2,
                localUpdatedAt = 1000,
                remoteEditRevision = 1,
                remoteUpdatedAt = 5000,
                snapshotEditRevision = 1
            )
        )
    }

    @Test
    fun canonicalResponseIsAdoptedWhenLocalRevisionMatchesSyncSnapshot() {
        assertTrue(
            MessageSyncConflictResolver.shouldAdoptCanonical(
                localEditRevision = 1,
                localUpdatedAt = 9000,
                remoteEditRevision = 1,
                remoteUpdatedAt = 1000,
                snapshotEditRevision = 1
            )
        )
    }

    @Test
    fun equalRevisionFallsBackToUpdatedAtWhenLocalChangedAfterSnapshot() {
        assertFalse(
            MessageSyncConflictResolver.shouldAdoptCanonical(
                localEditRevision = 1,
                localUpdatedAt = 9000,
                remoteEditRevision = 1,
                remoteUpdatedAt = 1000,
                snapshotEditRevision = 0
            )
        )

        assertTrue(
            MessageSyncConflictResolver.shouldAdoptCanonical(
                localEditRevision = 1,
                localUpdatedAt = 1000,
                remoteEditRevision = 1,
                remoteUpdatedAt = 9000,
                snapshotEditRevision = 0
            )
        )
    }
}
