package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            val documents = message.parts.filterIsInstance<UIMessagePart.Document>()
            if (documents.isEmpty()) {
                message
            } else {
                message.copy(
                    parts = buildList {
                        documents.forEach { document ->
                            add(UIMessagePart.Text(document.toUploadReminder()))
                        }
                        addAll(message.parts)
                    }
                )
            }
        }
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    internal fun UIMessagePart.Document.toUploadReminder(): String {
        val path = resolveWorkspacePath(this)
        return buildString {
            append("<UploadFile")
            append(" name=\"")
            append(fileName)
            append("\" mime=\"")
            append(mime)
            append("\"")
            if (path != null) {
                append(" path=\"")
                append(path)
                append("\"")
            }
            appendLine(">")
            append("The user uploaded this file. Do not assume its contents from the file name alone.")
            if (path != null) {
                append(" If workspace tools are available, read the original file from `$path`.")
            }
            appendLine()
            append("</UploadFile>")
        }
    }
}
