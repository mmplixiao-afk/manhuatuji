package com.manhuatuji.core

/**
 * 可选的成稿格式。拆分镜不走这里，自由文稿可以直接粘贴。
 * 只有文稿本身带「标题： / 封面字 / 正文： / 标签」时，导出才会排成这个版式。
 * 标题不超过 20 个字；封面正好两行；正文以提问收尾；标签单独一行并用空格分开。
 */
data class CopyDocument(
    val title: String,
    val coverLine1: String,
    val coverLine2: String,
    val body: String,
    val tags: String,
) {
    fun format(): String = buildString {
        append("标题：").append(title).append("\n\n")
        append("封面字（第二行红圈）：\n")
        append(coverLine1).append('\n')
        append(coverLine2).append("\n\n")
        append("正文：\n")
        append(body.trim()).append("\n\n")
        append(tags.trim()).append('\n')
    }

    companion object {
        /** 成稿能解析时返回排好的导出文本；自由文稿返回 null，调用方改用原稿。 */
        fun tryExport(raw: String): String? = runCatching { parse(raw).format() }.getOrNull()

        fun parse(raw: String): CopyDocument {
            val text = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim()
            require(text.isNotEmpty()) { "请先粘贴文案" }
            require(!text.contains("#小满说") && !text.contains("＃小满说")) { "不要出现 #小满说" }
            val lines = text.split('\n')
            val titleLine = lines.firstOrNull { isTitleHeader(it) }
                ?: throw IllegalArgumentException("缺少「标题：」")
            val title = remainderAfterColon(titleLine)
            require(title.isNotEmpty()) { "标题不能为空" }
            val titleLength = title.codePointCount(0, title.length)
            require(titleLength <= 20) { "标题不能超过 20 个字，当前 $titleLength 个字" }

            val coverHeader = lines.indexOfFirst { isCoverHeader(it) }
            require(coverHeader >= 0) { "缺少封面两行。请以「封面字（第二行红圈）：」单独起一行" }
            val bodyHeader = lines.indexOfFirst { isBodyHeader(it) }
            require(bodyHeader > coverHeader) { "缺少「正文：」，且要写在封面两行之后" }

            val inlineCover = remainderAfterColon(lines[coverHeader])
            val between = lines.subList(coverHeader + 1, bodyHeader).map { it.trim() }.filter { it.isNotEmpty() }
            val coverLines = if (inlineCover.isNotEmpty()) listOf(inlineCover) + between else between
            require(coverLines.size == 2) { "封面必须正好两行，第二行会画红圈。当前是 ${coverLines.size} 行" }

            val after = lines.subList(bodyHeader + 1, lines.size)
            val lastNonEmpty = after.indexOfLast { it.trim().isNotEmpty() }
            require(lastNonEmpty >= 0) { "正文不能为空，最后一行要单独写标签" }
            val tagLine = after[lastNonEmpty].trim().replace('＃', '#')
            require(tagLine.startsWith("#")) { "请把标签单独放在最后一行，例如 #真实故事 #婚姻" }
            validateTags(tagLine)
            val body = after.take(lastNonEmpty).joinToString("\n").trim()
            require(body.isNotEmpty()) { "正文不能为空" }
            require(body.codePointCount(0, body.length) >= 20) { "正文太短" }
            val paragraphs = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val last = paragraphs.last()
            require(last.contains('？') || last.contains('?')) { "正文最后一段应是一句提问" }
            return CopyDocument(title, coverLines[0], coverLines[1], body, tagLine)
        }

        private fun isTitleHeader(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("标题") && (trimmed.contains('：') || trimmed.contains(':'))
        }

        private fun isCoverHeader(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("封面字") || trimmed.startsWith("封面两行") ||
                trimmed.startsWith("封面：") || trimmed.startsWith("封面:")
        }

        private fun isBodyHeader(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("正文：") || trimmed.startsWith("正文:") || trimmed == "正文"
        }

        private fun remainderAfterColon(line: String): String {
            val trimmed = line.trim()
            val index = trimmed.indexOfFirst { it == '：' || it == ':' }
            if (index < 0) return ""
            return trimmed.substring(index + 1).trim()
        }

        fun validateTags(tags: String) {
            val normalized = tags.trim().replace('＃', '#')
            require(!normalized.contains("小满说")) { "不要出现 #小满说" }
            require(normalized.matches(Regex("^(#[^\\s#]+)( +#[^\\s#]+)*$"))) {
                "标签之间要有空格，例如 #真实故事 #婚姻 #家庭"
            }
        }
    }
}
