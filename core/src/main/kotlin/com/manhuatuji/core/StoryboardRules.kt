package com.manhuatuji.core

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
)

data class Storyboard(
    val characters: List<CharacterLock>,
    val panels: List<PanelSpec>,
)

object StoryboardRules {
    const val MAX_PAGES = 18
    val FORBIDDEN = listOf("律师", "律所", "法庭", "直播", "律师袍", "法袍", "律政")
    private val listenerWords = listOf("同学", "亲戚", "朋友", "倾听", "闺蜜", "邻居", "同事", "姐姐", "妹妹", "家人")

    fun parse(content: String): Storyboard {
        val root = JSONObject(extractJsonObject(content))
        val charactersJson = root.optJSONArray("characters")
            ?: throw IllegalArgumentException("分镜缺少 characters")
        val characters = (0 until charactersJson.length()).map { index ->
            val obj = charactersJson.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 个角色不是对象")
            CharacterLock(
                id = obj.optString("id").trim().ifBlank { "c$index" },
                name = obj.optString("name").trim(),
                role = obj.optString("role").trim(),
                appearance = obj.optString("appearance").trim(),
            )
        }
        val panelsJson = root.optJSONArray("panels")
            ?: throw IllegalArgumentException("分镜缺少 panels")
        require(panelsJson.length() > 0) { "分镜没有画面" }
        val panels = (0 until panelsJson.length()).map { index ->
            val obj = panelsJson.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 个画面不是对象")
            PanelSpec(
                id = index,
                kind = if (index == 0) "cover" else "page",
                caption = obj.optString("caption").trim(),
                scene = obj.optString("scene").ifBlank { obj.optString("visual") }.trim(),
                imagePrompt = obj.optString("image_prompt").ifBlank { obj.optString("imagePrompt") }.trim(),
                characterIds = readIds(obj),
            )
        }
        return Storyboard(characters, panels)
    }

    fun issues(copy: CopyDocument, board: Storyboard): List<String> {
        val found = mutableListOf<String>()
        if (board.characters.isEmpty()) found += "请锁定人物外貌"
        val xiaoman = board.characters.firstOrNull { it.name == "小满" }
        if (xiaoman == null) found += "分镜里要有叙述者小满"
        board.characters.forEach { character ->
            if (character.name.isBlank()) found += "有角色没有名字"
            if (character.appearance.codePointCount(0, character.appearance.length) < 8) {
                found += "${character.name.ifBlank { "角色" }}的外貌描述太短，无法锁定"
            }
            forbiddenHit(character.role)?.let { found += "${character.name}的身份里不要出现$it" }
            forbiddenHit(character.appearance)?.let { found += "${character.name}的外貌里不要出现$it" }
        }
        if (xiaoman != null && listenerWords.none { xiaoman.role.contains(it) }) {
            found += "小满应是同学、亲戚或朋友这类倾听者"
        }
        if (board.panels.isEmpty()) {
            found += "分镜没有画面"
            return found
        }
        if (board.panels.first().kind != "cover") found += "第一张必须是封面"
        val pages = board.panels.filter { it.kind == "page" }
        if (pages.isEmpty()) found += "至少要有一页内页"
        if (pages.size > MAX_PAGES) found += "内页最多 $MAX_PAGES 张，请删减后再确认"
        val known = board.characters.map { it.id }.toSet()
        board.panels.forEach { panel ->
            val label = panelLabel(board.panels, panel)
            if (panel.scene.isBlank()) found += "$label 缺少画面说明"
            if (panel.imagePrompt.isBlank()) found += "$label 缺少生图提示词"
            forbiddenHit(panel.scene)?.let { found += "$label 不要出现$it" }
            forbiddenHit(panel.caption)?.let { found += "$label 不要出现$it" }
            forbiddenHit(panel.imagePrompt)?.let { found += "$label 的生图提示词不要出现$it" }
            panel.characterIds.filter { it !in known }.forEach { found += "$label 提到了未知人物 $it" }
            if (panel.kind == "page") {
                if (panel.caption.isBlank()) found += "$label 缺少要叠上去的旁白"
                if (panel.characterIds.isEmpty()) found += "$label 要标明出场人物"
                panel.caption.split('\n').map { it.trim() }.filter { it.codePointCount(0, it.length) >= 6 }
                    .firstOrNull { panel.imagePrompt.contains(it) }
                    ?.let { found += "$label 的生图提示词不要写入旁白，文字由程序叠加" }
            }
            if (panel.kind == "cover") {
                if (panel.characterIds.isEmpty()) found += "封面要标明出场人物"
                if (panel.imagePrompt.contains(copy.coverLine1) || panel.imagePrompt.contains(copy.coverLine2)) {
                    found += "封面提示词不要包含标题文字，标题由程序叠加"
                }
            }
        }
        val firstPage = pages.firstOrNull()
        if (firstPage != null) {
            val xiaomanId = xiaoman?.id
            if (xiaomanId != null && xiaomanId !in firstPage.characterIds) {
                found += "第一页要先引出叙述者小满"
            }
            if (!firstPage.scene.contains("小满") && !firstPage.caption.contains("小满")) {
                found += "第一页的画面或旁白要出现小满"
            }
        }
        return found
    }

    fun validate(copy: CopyDocument, board: Storyboard) {
        val found = issues(copy, board)
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
        val number = panels.filter { it.kind == "page" }.indexOfFirst { it.id == panel.id } + 1
        return "第 $number 页"
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
