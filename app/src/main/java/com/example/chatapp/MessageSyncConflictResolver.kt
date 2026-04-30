package com.example.chatapp

object MessageSyncConflictResolver {
    fun shouldAdoptCanonical(
        localEditRevision: Int,
        localUpdatedAt: Long,
        remoteEditRevision: Int,
        remoteUpdatedAt: Long,
        snapshotEditRevision: Int?
    ): Boolean {
        if (remoteEditRevision > localEditRevision) return true
        if (remoteEditRevision < localEditRevision) return false

        return snapshotEditRevision == localEditRevision || remoteUpdatedAt >= localUpdatedAt
    }
}
