package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergePolicyTest {
    @Test
    fun `conversation import keeps missing and newer backup rows only`() {
        assertTrue(shouldImportBackupConversation(currentUpdateAt = null, backupUpdateAt = 100L))
        assertTrue(shouldImportBackupConversation(currentUpdateAt = 100L, backupUpdateAt = 101L))
        assertFalse(shouldImportBackupConversation(currentUpdateAt = 100L, backupUpdateAt = 100L))
        assertFalse(shouldImportBackupConversation(currentUpdateAt = 101L, backupUpdateAt = 100L))
    }

    @Test
    fun `memory content key ignores surrounding and repeated whitespace`() {
        assertEquals(
            "user prefers concise replies",
            normalizeMemoryContent("  user   prefers\nconcise\t replies  "),
        )
    }

    @Test
    fun `backup reindex only touches changed conversations when index is ready`() {
        val plan = chooseBackupReindexPlan(
            indexReady = true,
            changedConversationIds = listOf("c2", "c1", "c2"),
            allConversationIds = emptyList(),
        )

        assertEquals(listOf("c2", "c1"), plan.conversationIds)
        assertFalse(plan.clearExistingIndex)
        assertFalse(plan.markReadyWhenDone)
    }

    @Test
    fun `backup reindex marks ready when changed conversations cover an unready index`() {
        val plan = chooseBackupReindexPlan(
            indexReady = false,
            changedConversationIds = listOf("c1", "c2", "c1"),
            allConversationIds = listOf("c1", "c2"),
        )

        assertEquals(listOf("c1", "c2"), plan.conversationIds)
        assertTrue(plan.clearExistingIndex)
        assertTrue(plan.markReadyWhenDone)
    }

    @Test
    fun `backup reindex rebuilds all conversations when unready index has unchanged rows`() {
        val plan = chooseBackupReindexPlan(
            indexReady = false,
            changedConversationIds = listOf("c2"),
            allConversationIds = listOf("c1", "c2", "c3"),
        )

        assertEquals(listOf("c1", "c2", "c3"), plan.conversationIds)
        assertTrue(plan.clearExistingIndex)
        assertTrue(plan.markReadyWhenDone)
    }
}
