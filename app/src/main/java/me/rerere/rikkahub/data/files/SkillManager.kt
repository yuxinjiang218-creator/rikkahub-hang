package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        private const val BUNDLED_SKILLS_ASSET_ROOT = "builtin_skills"
        private const val BUNDLED_SKILLS_MARKER_FILE = ".rikkahub-builtin-skills-installed"
        const val SKILLS_SYNC_META_FILE = ".rikkahub-skills-meta.json"
    }

    private val metadataJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val bundledSkillDirectoryNames = listOf("skill-creator")

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        ensureBundledSkillsInstalled(dir)
        ensureSkillSyncIndex(dir)
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        touchSkillSyncMetadata(name)
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            removeSkillSyncMetadata(name)
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        touchSkillSyncMetadata(skillName)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            touchSkillSyncMetadata(skillName)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val deleted = target.delete()
        if (deleted) {
            touchSkillSyncMetadata(skillName)
        }
        return deleted
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    fun markSkillModified(skillName: String) {
        touchSkillSyncMetadata(skillName)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    fun mergeSkillsFromBackup(backupSkillsRoot: File): SkillMergeResult {
        val skillsRoot = getSkillsDir()
        val localIndex = ensureSkillSyncIndex(skillsRoot).skills.toMutableMap()
        val backupIndex = readSkillSyncIndex(backupSkillsRoot).skills
        var inserted = 0
        var updated = 0
        var skipped = 0

        backupSkillsRoot.listFiles()
            ?.filter { it.isDirectory && it.resolve("SKILL.md").exists() }
            ?.sortedBy { it.name }
            ?.forEach { backupDir ->
                val skillName = backupDir.name
                val localDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
                    ?: run {
                        skipped++
                        return@forEach
                    }
                val backupHash = computeSkillContentHash(backupDir)
                val backupMeta = backupIndex[skillName]
                    ?: SkillSyncMetadata(updatedAt = 0L, contentHash = backupHash)

                if (!localDir.exists()) {
                    backupDir.copyRecursively(localDir, overwrite = true)
                    localIndex[skillName] = backupMeta.copy(contentHash = backupHash)
                    inserted++
                    return@forEach
                }

                val localHash = computeSkillContentHash(localDir)
                if (localHash == backupHash) {
                    localIndex[skillName] = (localIndex[skillName] ?: SkillSyncMetadata()).copy(contentHash = localHash)
                    skipped++
                    return@forEach
                }

                val localUpdatedAt = localIndex[skillName]?.updatedAt ?: 0L
                if (backupMeta.updatedAt > 0L && backupMeta.updatedAt > localUpdatedAt) {
                    replaceSkillDirectory(localDir, backupDir)
                    localIndex[skillName] = backupMeta.copy(contentHash = backupHash)
                    updated++
                } else {
                    localIndex[skillName] = (localIndex[skillName] ?: SkillSyncMetadata()).copy(contentHash = localHash)
                    skipped++
                }
            }

        writeSkillSyncIndex(skillsRoot, SkillSyncIndex(skills = localIndex.filterExistingSkills(skillsRoot)))
        return SkillMergeResult(
            inserted = inserted,
            updated = updated,
            skipped = skipped,
        )
    }

    private fun touchSkillSyncMetadata(skillName: String) {
        val skillsRoot = getSkillsDir()
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName) ?: return
        if (!skillDir.exists()) return
        val index = readSkillSyncIndex(skillsRoot).skills.toMutableMap()
        index[skillName] = SkillSyncMetadata(
            updatedAt = System.currentTimeMillis(),
            contentHash = computeSkillContentHash(skillDir),
        )
        writeSkillSyncIndex(skillsRoot, SkillSyncIndex(skills = index.filterExistingSkills(skillsRoot)))
    }

    private fun removeSkillSyncMetadata(skillName: String) {
        val skillsRoot = getSkillsDir()
        val index = readSkillSyncIndex(skillsRoot).skills.toMutableMap()
        index.remove(skillName)
        writeSkillSyncIndex(skillsRoot, SkillSyncIndex(skills = index.filterExistingSkills(skillsRoot)))
    }

    private fun ensureSkillSyncIndex(skillsRoot: File): SkillSyncIndex {
        val existingIndex = readSkillSyncIndex(skillsRoot)
        val next = existingIndex.skills.toMutableMap()
        skillsRoot.listFiles()
            ?.filter { it.isDirectory && it.resolve("SKILL.md").exists() }
            ?.forEach { skillDir ->
                if (skillDir.name !in next) {
                    next[skillDir.name] = SkillSyncMetadata(
                        updatedAt = 0L,
                        contentHash = computeSkillContentHash(skillDir),
                    )
                }
            }
        val filtered = next.filterExistingSkills(skillsRoot)
        val normalized = SkillSyncIndex(skills = filtered)
        if (normalized != existingIndex) {
            writeSkillSyncIndex(skillsRoot, normalized)
        }
        return normalized
    }

    private fun readSkillSyncIndex(skillsRoot: File): SkillSyncIndex {
        val file = skillsRoot.resolve(SKILLS_SYNC_META_FILE)
        if (!file.exists()) return SkillSyncIndex()
        return runCatching {
            metadataJson.decodeFromString<SkillSyncIndex>(file.readText())
        }.getOrElse {
            Log.w(TAG, "readSkillSyncIndex: Failed to read ${file.absolutePath}", it)
            SkillSyncIndex()
        }
    }

    private fun writeSkillSyncIndex(skillsRoot: File, index: SkillSyncIndex) {
        skillsRoot.mkdirs()
        skillsRoot.resolve(SKILLS_SYNC_META_FILE).writeText(metadataJson.encodeToString(index))
    }

    private fun Map<String, SkillSyncMetadata>.filterExistingSkills(skillsRoot: File): Map<String, SkillSyncMetadata> {
        return filterKeys { skillName ->
            skillsRoot.resolve(skillName).resolve("SKILL.md").exists()
        }.toSortedMap()
    }

    private fun computeSkillContentHash(skillDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        skillDir.walkTopDown()
            .filter { it.isFile && it.name != SKILLS_SYNC_META_FILE && it.name != BUNDLED_SKILLS_MARKER_FILE }
            .map { it.relativeTo(skillDir).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .forEach { (relativePath, file) ->
                digest.update(relativePath.toByteArray())
                digest.update(0.toByte())
                digest.update(file.readBytes())
                digest.update(0.toByte())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun replaceSkillDirectory(localDir: File, backupDir: File) {
        val skillsRoot = localDir.parentFile ?: error("Skill directory has no parent: ${localDir.absolutePath}")
        val backupLocalDir = createTempSkillDir(skillsRoot, localDir.name, "merge-backup")
            ?: error("Failed to create temporary skill backup dir: ${localDir.name}")

        try {
            if (localDir.exists() && !localDir.renameTo(backupLocalDir)) {
                error("Failed to move current skill dir: ${localDir.absolutePath}")
            }
            backupDir.copyRecursively(localDir, overwrite = true)
            backupLocalDir.deleteRecursively()
        } catch (e: Throwable) {
            if (!localDir.exists() && backupLocalDir.exists()) {
                backupLocalDir.renameTo(localDir)
            }
            throw e
        } finally {
            if (backupLocalDir.exists() && localDir.exists()) {
                backupLocalDir.deleteRecursively()
            }
        }
    }

    private fun ensureBundledSkillsInstalled(skillsRoot: File) {
        val installed = readBundledSkillsMarker(skillsRoot).toMutableSet()
        var changed = false
        bundledSkillDirectoryNames.forEach { directoryName ->
            val targetDir = skillsRoot.resolve(directoryName)
            if (targetDir.exists()) {
                if (installed.add(directoryName)) changed = true
                return@forEach
            }
            if (directoryName in installed) return@forEach
            copyAssetDirectory("$BUNDLED_SKILLS_ASSET_ROOT/$directoryName", targetDir)
            installed += directoryName
            changed = true
        }
        if (changed) writeBundledSkillsMarker(skillsRoot, installed)
    }

    private fun readBundledSkillsMarker(skillsRoot: File): Set<String> {
        val file = skillsRoot.resolve(BUNDLED_SKILLS_MARKER_FILE)
        if (!file.exists()) return emptySet()
        return file.readLines().mapTo(linkedSetOf()) { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    private fun writeBundledSkillsMarker(skillsRoot: File, installed: Set<String>) {
        skillsRoot.resolve(BUNDLED_SKILLS_MARKER_FILE).writeText(installed.sorted().joinToString(separator = "\n"))
    }

    private fun copyAssetDirectory(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetDirectory("$assetPath/$child", target.resolve(child))
        }
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

@Serializable
data class SkillSyncIndex(
    val skills: Map<String, SkillSyncMetadata> = emptyMap(),
)

@Serializable
data class SkillSyncMetadata(
    val updatedAt: Long = 0L,
    val contentHash: String = "",
)

data class SkillMergeResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(content) ?: return result
        val yaml = content.substring(3, endRange.first).trim()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
