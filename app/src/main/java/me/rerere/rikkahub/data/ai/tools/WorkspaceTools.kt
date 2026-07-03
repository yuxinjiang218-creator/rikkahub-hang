package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
private const val SVG_DEFAULT_SIZE = 1024
private const val SVG_MAX_DIMENSION = 4096

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_view_image" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createViewImageTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun String.imageMimeTypeFromPath(): String? = when (substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    else -> null
}

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area, and /upload for read-only files uploaded by the user.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg). Image files are returned as a visible image result plus JSON metadata.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createViewImageTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_view_image",
    description = """
        View an image file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area, and /upload for read-only files uploaded by the user.
        Supports png, jpg, jpeg, gif, webp, bmp, and svg. The image is returned as a visible image result that the model can inspect, plus JSON metadata.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_view_image") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        require(path.isImagePath()) { "Path is not a supported image file: $path" }
        workspaceRepository.readImageInRootfs(workspaceId, path)
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val uploadFile = resolveUploadFile(path)
    if (uploadFile != null) {
        val size = uploadFile.length()
        require(size <= MAX_READ_FILE_BYTES) {
            "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
        }
        return uploadFile.readText(Charsets.UTF_8)
    }

    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

private fun rootfsPathToAreaAndRelative(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val uploadFile = resolveUploadFile(path)
    val bytes = if (uploadFile != null) {
        uploadFile.readBytes()
    } else {
        val (area, relativePath) = rootfsPathToAreaAndRelative(path)
        val buffer = ByteArrayOutputStream()
        exportFile(workspaceId, area, relativePath, buffer)
        buffer.toByteArray()
    }
    require(bytes.size <= MAX_IMAGE_BYTES) {
        "Image is too large to view: $path (${bytes.size / 1024 / 1024}MB, max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)."
    }

    val sourceMimeType = path.imageMimeTypeFromPath() ?: "image/png"
    val encoded = encodeWorkspaceImageForVision(bytes, sourceMimeType)
    return listOf(
        UIMessagePart.Image(url = encoded.dataUrl),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("mime_type", encoded.mimeType)
                put("source_mime_type", sourceMimeType)
                put("size_bytes", bytes.size)
                put("description", "Image file read successfully")
                put("visible_image", true)
            }.toString()
        ),
    )
}

private data class EncodedWorkspaceImage(
    val mimeType: String,
    val dataUrl: String,
)

private fun encodeWorkspaceImageForVision(bytes: ByteArray, mimeType: String): EncodedWorkspaceImage {
    val (outputMimeType, outputBytes) = when (mimeType) {
        "image/svg+xml" -> "image/png" to rasterizeSvgToPng(bytes)
        "image/bmp" -> "image/png" to rasterizeBitmapToPng(bytes)
        "image/webp" -> "image/png" to rasterizeBitmapToPng(bytes)
        else -> mimeType to bytes
    }
    return EncodedWorkspaceImage(
        mimeType = outputMimeType,
        dataUrl = "data:$outputMimeType;base64,${Base64.encodeToString(outputBytes, Base64.NO_WRAP)}",
    )
}

private fun rasterizeSvgToPng(bytes: ByteArray): ByteArray {
    val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
    val viewBox = svg.documentViewBox
    val rawWidth = svg.documentWidth.takeIf { it > 0f } ?: viewBox?.width() ?: SVG_DEFAULT_SIZE.toFloat()
    val rawHeight = svg.documentHeight.takeIf { it > 0f } ?: viewBox?.height() ?: SVG_DEFAULT_SIZE.toFloat()
    val scale = min(1f, SVG_MAX_DIMENSION / maxOf(rawWidth, rawHeight))
    val width = (rawWidth * scale).roundToInt().coerceAtLeast(1).coerceAtMost(SVG_MAX_DIMENSION)
    val height = (rawHeight * scale).roundToInt().coerceAtLeast(1).coerceAtMost(SVG_MAX_DIMENSION)
    svg.setDocumentWidth(width.toFloat())
    svg.setDocumentHeight(height.toFloat())

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return bitmap.usePngBytes {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        svg.renderToCanvas(canvas)
    }
}

private fun rasterizeBitmapToPng(bytes: ByteArray): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("Failed to decode image bytes")
    return bitmap.usePngBytes()
}

private inline fun Bitmap.usePngBytes(draw: () -> Unit = {}): ByteArray {
    return try {
        draw()
        ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to encode PNG image" }
            output.toByteArray()
        }
    } finally {
        recycle()
    }
}

private fun resolveUploadFile(path: String): File? {
    val trimmed = path.trimEnd('/')
    if (trimmed != "/upload" && !trimmed.startsWith("/upload/")) return null
    val relativePath = trimmed.removePrefix("/upload").trimStart('/')
    require(relativePath.isNotBlank()) { "Path is not a file: $path" }
    val context = getKoin().get<Context>()
    val uploadDir = File(context.filesDir, FileFolders.UPLOAD).canonicalFile
    val file = File(uploadDir, relativePath).canonicalFile
    require(file.path == uploadDir.path || file.path.startsWith(uploadDir.path + File.separator)) {
        "Path escapes upload directory: $path"
    }
    require(file.exists()) { "File does not exist: $path" }
    require(file.isFile) { "Path is not a file: $path" }
    return file
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
