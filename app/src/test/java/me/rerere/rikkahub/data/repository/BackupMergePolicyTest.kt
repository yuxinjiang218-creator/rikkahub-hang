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
}
