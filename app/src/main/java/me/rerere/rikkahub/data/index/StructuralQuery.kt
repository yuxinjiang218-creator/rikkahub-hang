package me.rerere.rikkahub.data.index

/**
 * 判断查询是不是"结构化标识符"（路径、UUID、错误码、长哈希、URL）。
 *
 * 这类查询靠词法精确命中就够了，额外花一次 embedding 请求既慢又没必要。
 */
object StructuralQuery {
    private val patterns = listOf(
        // 绝对路径 / Windows 盘符 / 相对路径
        Regex("""(^|\s)(/|[A-Za-z]:\\|\.{1,2}/)\S+"""),
        // 多段路径（无空格）
        Regex("""^[\w.\-]+(/[\w.\-]+){2,}$"""),
        // 带已知扩展名的文件名
        Regex("""\b[\w-]+\.(kt|java|gradle|kts|md|json|xml|yml|yaml|toml|sql|db|log|sh|py|ts|js|rs|cpp|c|h)\b"""),
        // UUID
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""),
        // 错误码 / 状态码
        Regex("""\b(ERR|HTTP|SQLITE|E|W)[-_ ]?\d{2,5}\b""", RegexOption.IGNORE_CASE),
        // errno 与 SQLite 常量名
        Regex(
            """\b(SQLITE_[A-Z_]+|EAI_[A-Z_]+|ENOENT|EACCES|EPERM|ENOMEM|EINVAL|ECONNRESET|ECONNREFUSED|ETIMEDOUT|ENOTFOUND)\b"""
        ),
        // 长十六进制（哈希、提交号）
        Regex("""\b[0-9a-f]{16,}\b""", RegexOption.IGNORE_CASE),
        // 反引号包裹的标识符
        Regex("""`[^`]+`"""),
        // 完整 URL
        Regex("""https?://\S+""")
    )

    fun isStructural(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        return patterns.any { it.containsMatchIn(trimmed) }
    }
}
