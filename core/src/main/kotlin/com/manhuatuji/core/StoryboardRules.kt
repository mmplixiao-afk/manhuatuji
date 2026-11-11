package com.manhuatuji.core

import org.json.JSONArray
import org.json.JSONObject

data class CharacterLock(
    val id: String,
    val name: String,
    val role: String,
    val appearance: String,
)

data class PanelSpec(
    val id: Int,
    val kind: String,
    val caption: String,
    val scene: String,
    val imagePrompt: String,
    val characterIds: List<String> = emptyList(),
    val subtitleChunks: List<String> = emptyList(),
)

data class Storyboard(
    val characters: List<CharacterLock>,
    val panels: List<PanelSpec>,
)

object StoryboardRules {
    const val MAX_PAGES = 18
    val FORBIDDEN = listOf("律师", "律所", "法庭", "直播", "律师袍", "法袍", "律政")
    private val listenerWords = listOf("同学", "亲戚", "朋友", "倾听", "闺蜜", "邻居", "同事", "姐姐", "妹妹", "家人")

    /**
     * 只统一换行和去掉文首 BOM。不删空格、标点或正文，分镜旁白要和这份文本逐字符对齐。
     */
    fun normalizeManuscript(raw: String): String =
        raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')

    fun canSplit(raw: String): Boolean = normalizeManuscript(raw).isNotBlank()

    fun parse(content: String): Storyboard {
        val root = JSONObject(extractJsonObject(content))
        val characters = parseCharacters(root.optJSONArray("characters"))
        val shots = root.optJSONArray("shots") ?: root.optJSONArray("panels")
            ?: throw IllegalArgumentException("分镜缺少 shots")
        require(shots.length() > 0) { "分镜没有镜头" }
        val panels = (0 until shots.length()).map { index ->
            val obj = shots.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 镜不是对象")
            val chunks = readChunks(obj)
            val voiceover = obj.optString("voiceover")
                .ifEmpty { obj.optString("caption") }
                .ifEmpty { chunks.joinToString("") }
            val kind = when {
                obj.optString("kind") == "cover" -> "cover"
                voiceover.isEmpty() && obj.optBoolean("cover", false) -> "cover"
                else -> "page"
            }
            PanelSpec(
                id = index,
                kind = kind,
                caption = voiceover,
                scene = obj.optString("visual").ifBlank { obj.optString("scene") }.trim(),
                imagePrompt = obj.optString("image_prompt").ifBlank { obj.optString("imagePrompt") }.trim(),
                characterIds = readIds(obj),
                subtitleChunks = chunks,
            )
        }
        return Storyboard(characters, panels)
    }

    fun manuscriptPanels(board: Storyboard): List<PanelSpec> = board.panels.filter { it.kind != "cover" }

    fun joinedVoiceover(board: Storyboard): String = manuscriptPanels(board).joinToString("") { it.caption }

    /** 每一镜原文都是原稿里的连续片段，按顺序拼回去必须等于归一化后的原稿。封面空旁白不参与。 */
    fun coversManuscript(manuscript: String, board: Storyboard): Boolean {
        val expected = normalizeManuscript(manuscript)
        var cursor = 0
        for (panel in manuscriptPanels(board)) {
            val text = panel.caption
            if (text.isEmpty() || !expected.startsWith(text, cursor)) return false
            cursor += text.length
        }
        return cursor == expected.length
    }

    fun blockingIssues(manuscript: String, board: Storyboard): List<String> {
        val found = mutableListOf<String>()
        val pages = manuscriptPanels(board)
        if (pages.isEmpty()) found += "没有可显示的分镜"
        if (pages.isNotEmpty() && !coversManuscript(manuscript, board)) {
            found += "旁白按顺序拼接后与原稿不一致"
        }
        pages.forEachIndexed { index, panel ->
            val label = "第 ${index + 1} 镜"
            if (panel.caption.isEmpty()) found += "$label 没有原文"
            if (panel.scene.isBlank()) found += "$label 缺少画面"
            if (panel.imagePrompt.isBlank()) found += "$label 缺少生图提示词"
        }
        if (pages.size > MAX_PAGES) found += "内页最多 $MAX_PAGES 张，请删减或按反馈重拆"
        return found
    }

    /** 风格提醒。不阻止把分镜显示出来，确认出图前仍建议看一眼。 */
    fun warnings(board: Storyboard): List<String> {
        val found = mutableListOf<String>()
        val pages = manuscriptPanels(board)
        val first = pages.firstOrNull()
        if (first != null && !first.scene.contains("小满") && !first.caption.contains("小满")) {
            found += "第一镜画面没有出现小满。旁白是「我」在听的时候，可以把倾听者画成小满"
        }
        board.characters.forEach { character ->
            if (character.name == "小满" && listenerWords.none { character.role.contains(it) }) {
                found += "小满应是同学、亲戚或朋友这类倾听者"
            }
            forbiddenHit(character.role)?.let { found += "${character.name}的身份里不要出现$it" }
            forbiddenHit(character.appearance)?.let { found += "${character.name}的外貌里不要出现$it" }
        }
        if (board.characters.isEmpty()) found += "这次没有单独锁定人物外貌，连续性靠每镜的画面描述"
        board.panels.forEach { panel ->
            val label = panelLabel(board.panels, panel)
            forbiddenHit(panel.scene)?.let { found += "$label 画面里不要出现$it" }
            forbiddenHit(panel.imagePrompt)?.let { found += "$label 的生图提示词不要出现$it" }
            if (panel.subtitleChunks.isNotEmpty() && panel.subtitleChunks.joinToString("") != panel.caption) {
                found += "$label 的字幕分段拼接后与原文不一致"
            }
            panel.caption.split('\n').map { it.trim() }.filter { it.codePointCount(0, it.length) >= 6 }
                .firstOrNull { panel.imagePrompt.contains(it) }
                ?.let { found += "$label 的生图提示词不要写入旁白，文字由程序叠加" }
        }
        return found
    }

    fun validateForImages(manuscript: String, board: Storyboard) {
        val found = blockingIssues(manuscript, board)
        if (found.isNotEmpty()) throw IllegalArgumentException(found.joinToString("；"))
    }

    /** 先出小满所在的第一页，作为后续封面和其他页的角色参考。 */
    fun drawOrder(board: Storyboard): List<PanelSpec> {
        val pages = board.panels.filter { it.kind == "page" }
        val cover = board.panels.filter { it.kind == "cover" }
        val anchor = pages.firstOrNull()
        return listOfNotNull(anchor) + cover + pages.drop(1)
    }

    fun pageNumber(panels: List<PanelSpec>, panel: PanelSpec): Int {
        require(panel.kind == "page") { "封面没有内页序号" }
        val index = panels.filter { it.kind == "page" }.indexOfFirst { it.id == panel.id }
        require(index >= 0) { "找不到这一页" }
        return index + 1
    }

    fun requireConfirmed(storyboardConfirmed: Boolean) {
        if (!storyboardConfirmed) {
            throw IllegalStateException("请先点「确认出图」，确认分镜后才会生图")
        }
    }

    fun forbiddenHit(text: String): String? {
        var cleaned = text
        FORBIDDEN.forEach { word ->
            cleaned = cleaned
                .replace("不是$word", "")
                .replace("不要$word", "")
                .replace("非$word", "")
                .replace("没有$word", "")
                .replace("勿画$word", "")
                .replace("别出现$word", "")
        }
        return FORBIDDEN.firstOrNull { cleaned.contains(it) }
    }

    private fun panelLabel(panels: List<PanelSpec>, panel: PanelSpec): String {
        if (panel.kind == "cover") return "封面"
        val number = panels.filter { it.kind != "cover" }.indexOfFirst { it.id == panel.id } + 1
        return "第 $number 镜"
    }

    private fun parseCharacters(array: JSONArray?): List<CharacterLock> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            CharacterLock(
                id = obj.optString("id").trim().ifBlank { "c$index" },
                name = name,
                role = obj.optString("role").trim(),
                appearance = obj.optString("appearance").trim(),
            )
        }
    }

    private fun readChunks(obj: JSONObject): List<String> {
        val array = obj.optJSONArray("subtitle_chunks") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                null, JSONObject.NULL -> null
                is String -> value
                else -> value.toString()
            }
        }
    }

    private fun readIds(obj: JSONObject): List<String> {
        val array = obj.optJSONArray("character_ids") ?: obj.optJSONArray("characters") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                null, JSONObject.NULL -> Unit
                is String -> if (value.isNotBlank()) ids += value.trim()
                is JSONObject -> {
                    val id = value.optString("id").ifBlank { value.optString("name") }.trim()
                    if (id.isNotBlank()) ids += id
                }
            }
        }
        return ids
    }

    private fun extractJsonObject(content: String): String {
        var trimmed = content.trim()
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            trimmed = trimmed.removeSuffix("```").trim()
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "分镜不是 JSON 对象" }
        return trimmed.substring(start, end + 1)
    }
}

object ExportNames {
    const val COPY = "文案.txt"

    fun cover(): String = "00_封面.png"

    fun page(number: Int): String {
        require(number in 1..98) { "页码超出范围" }
        return "%02d.png".format(number)
    }

    fun all(pageCount: Int): List<String> = buildList {
        add(cover())
        for (number in 1..pageCount) add(page(number))
        add(COPY)
    }
}
