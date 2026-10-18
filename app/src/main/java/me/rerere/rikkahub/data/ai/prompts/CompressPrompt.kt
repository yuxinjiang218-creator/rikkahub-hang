package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.ui.UIMessage

/** 默认的压缩提示词，可在模型设置中完整修改。 */
internal val DEFAULT_COMPRESS_PROMPT = """
    你是一个对话摘要维护者。你的唯一任务是根据本次请求提供的对话上下文，输出一份更新后的完整主摘要，让后续对话可以无缝继续，不需要回看已压缩的原始消息。
    输入可能包含上一次的主摘要，以及摘要之后的新消息。你的输出必须是新的完整主摘要，不是追加在旧摘要后的一段补充。
    不要调用工具。只输出纯文本摘要，不要解释处理过程。使用以下结构，不得省略字段；没有可靠内容时写“暂无新增确认”。

    [Summary of previous conversation]

    ## 当前工作态
    当前目标：
    活跃约束：
    未决问题：
    最近推进：
    下一步：

    ## 连续主线
    使用项目符号，保留仍在延续的任务线、创作线、关系线和需要后续回调的承诺。

    ## 时间推进
    使用项目符号，保留事件顺序、状态变化和决策转折；不要把有时序意义的内容压成静态标签。

    ## 关键锚点
    使用项目符号，保留后续难以准确重建的原句、关键表达、偏好、明确边界、用户否定或纠正过的方向，以及重要情绪语境。

    ## 决策与结论
    已作出的选择、得到的答案及必要理由。

    ## 重要结果与材料
    后续需要的文件路径、命令、数值、链接和调查结果，尽量原样保留准确值。

    ## 未决事项
    尚未完成的任务、需要确认的问题和用户正在等待的结果。

    信息处理规则：
    - 内容使用当前对话的语言；无法判断时使用 {locale}。
    - 新消息可以补充、纠正、覆盖旧信息或使旧方向失效；请判断后整合，不要机械拼接。
    - 不确定的信息标记为【待确认】，不要补造事实。
    - 精确保留重要原句、人名、地名、时间、用户立场、用户否定过的方案和约定的表达风格。
    - 除明确矛盾或被放弃的内容、纯重复内容外，不要为了简短删除仍有效的信息。宁可摘要较长，也不要丢失后续需要的背景。
    - 如果删除某条内容会让下一轮回复可能出现偏差，就必须保留。
""".trimIndent()

/** 构造压缩请求末尾的指令。自定义提示词可以使用 {locale}。 */
internal fun buildCompressPrompt(
    prompt: String,
    additionalPrompt: String,
    locale: String,
    keepRecentMessages: Int,
    sourceMessages: List<UIMessage>,
): String = buildString {
    val template = prompt.ifBlank { DEFAULT_COMPRESS_PROMPT }.trim()
    val previousSummary = sourceMessages.lastOrNull { it.isContextCheckpoint }?.toText().orEmpty()
    val incrementalMessages = sourceMessages
        .filterNot { it.isContextCheckpoint }
        .joinToString("\n\n") { it.summaryAsText() }
    val content = buildString {
        if (previousSummary.isNotBlank()) {
            appendLine("[Previous compressed summary]")
            appendLine(previousSummary.trim())
            appendLine()
        }
        append(incrementalMessages)
    }
    val additionalContext = buildString {
        if (additionalPrompt.isNotBlank()) {
            appendLine("Additional instructions from user: $additionalPrompt")
        }
        append("Keep recent visible messages outside compression: $keepRecentMessages")
    }
    val replacements = mapOf(
        "locale" to locale,
        "dialogue_summary_text" to previousSummary,
        "incremental_messages" to incrementalMessages,
        "additional_context" to additionalContext,
        "content" to content,
        "target_tokens" to "2000",
    )
    val expanded = Regex("""\{([^{}]+)}""").replace(template) { match ->
        replacements[match.groupValues[1]] ?: match.value
    }
    append(expanded)
    if ("{additional_context}" !in template) {
        appendLine()
        appendLine()
        append("本次保留的最近 $keepRecentMessages 条消息会原样放在摘要之前，因此它们没有包含在本次待总结内容中。")
        if (additionalPrompt.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("用户追加的要求：")
            append(additionalPrompt.trim())
        }
    }
}.trim()
