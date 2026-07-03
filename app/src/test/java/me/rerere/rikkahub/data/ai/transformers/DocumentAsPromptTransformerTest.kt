package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAsPromptTransformerTest {
    @Test
    fun `upload reminder names file without inlining document text`() {
        val document = UIMessagePart.Document(
            url = "content://example/report.pdf",
            fileName = "report.pdf",
            mime = "application/pdf",
        )

        val reminder = with(DocumentAsPromptTransformer) { document.toUploadReminder() }

        assertTrue(reminder.contains("<UploadFile"))
        assertTrue(reminder.contains("name=\"report.pdf\""))
        assertTrue(reminder.contains("mime=\"application/pdf\""))
        assertTrue(reminder.contains("The user uploaded this file."))
        assertTrue(reminder.contains("Do not assume its contents"))
    }
}
