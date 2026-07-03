package me.rerere.rikkahub.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCleanupPolicyTest {
    @Test
    fun `file at retention boundary is expired`() {
        val now = FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS * 2
        assertTrue(
            UploadCleanupPolicy.isExpired(
                timestampMillis = now - FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS,
                nowMillis = now,
                maxAgeMillis = FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS,
            )
        )
    }

    @Test
    fun `file newer than retention is kept`() {
        val now = FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS * 2
        assertFalse(
            UploadCleanupPolicy.isExpired(
                timestampMillis = now - FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS + 1,
                nowMillis = now,
                maxAgeMillis = FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS,
            )
        )
    }

    @Test
    fun `invalid timestamps are kept`() {
        assertFalse(
            UploadCleanupPolicy.isExpired(
                timestampMillis = 0,
                nowMillis = 10_000L,
                maxAgeMillis = FilesManager.DEFAULT_UPLOAD_RETENTION_MILLIS,
            )
        )
    }
}
