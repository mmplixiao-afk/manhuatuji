package com.manhuatuji.app

import android.content.Context
import com.manhuatuji.core.CharacterLock
import com.manhuatuji.core.CopyDocument
import com.manhuatuji.core.ExportNames
import com.manhuatuji.core.PanelSpec
import com.manhuatuji.core.Storyboard
import com.manhuatuji.core.StoryboardRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class Panel(
    val id: Int,
    val kind: String,
    val caption: String,
    val scene: String,
    val imagePrompt: String,
    val characterIds: List<String> = emptyList(),
    val imageStatus: String = "pending",
    val retries: Int = 0,
    val error: String = "",
) {
    fun spec() = PanelSpec(id, kind, caption, scene, imagePrompt, characterIds)

    fun json() = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("caption", caption)
        .put("scene", scene)
        .put("image_prompt", imagePrompt)
        .put("character_ids", JSONArray(characterIds))
        .put("image_status", imageStatus)
        .put("retries", retries)
        .put("error", error)

    companion object {
        fun from(value: JSONObject) = Panel(
            id = value.optInt("id"),
            kind = value.optString("kind", "page"),
            caption = value.optString("caption"),
            scene = value.optString("scene"),
            imagePrompt = value.optString("image_prompt"),
            characterIds = mutableListOf<String>().apply {
                val array = value.optJSONArray("character_ids") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optString(index)
                    if (item.isNotBlank()) add(item)
                }
            },
            imageStatus = value.optString("image_status", "pending"),
            retries = value.optInt("retries"),
            error = value.optString("error"),
        )
    }
}

data class ComicProject(
    val id: String,
    var title: String,
    val createdAt: Long = System.currentTimeMillis(),
    var rawCopy: String = "",
    var copy: CopyDocument? = null,
    var characters: MutableList<CharacterLock> = mutableListOf(),
    var panels: MutableList<Panel> = mutableListOf(),
    var stage: String = "copy",
    var status: String = "draft",
    var detail: String = "",
    var storyboardConfirmed: Boolean = false,
    var storyboardSource: String = "",
    var referenceSupported: Boolean = true,
    var taskStartedAt: Long = 0L,
    var completed: Int = 0,
    var total: Int = 0,
) {
    fun storyboard(): Storyboard = Storyboard(characters.toList(), panels.map { it.spec() })

    fun json() = JSONObject()
        .put("schema_version", 1)
        .put("id", id)
        .put("title", title)
        .put("created_at", createdAt)
        .put("raw_copy", rawCopy)
        .put("copy", copy?.let { copyJson(it) } ?: JSONObject.NULL)
        .put("characters", JSONArray().apply { characters.forEach { put(characterJson(it)) } })
        .put("panels", JSONArray().apply { panels.forEach { put(it.json()) } })
        .put("stage", stage)
        .put("status", status)
        .put("detail", detail)
        .put("storyboard_confirmed", storyboardConfirmed)
        .put("storyboard_source", storyboardSource)
        .put("reference_supported", referenceSupported)
        .put("task_started_at", taskStartedAt)
        .put("completed", completed)
        .put("total", total)

    companion object {
        fun from(value: JSONObject): ComicProject {
            val copyObject = value.optJSONObject("copy")
            return ComicProject(
                id = value.getString("id"),
                title = value.optString("title", "未命名图集"),
                createdAt = value.optLong("created_at"),
                rawCopy = value.optString("raw_copy"),
                copy = copyObject?.let { copyFrom(it) },
                characters = mutableListOf<CharacterLock>().apply {
                    val array = value.optJSONArray("characters") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { add(characterFrom(it)) }
                    }
                },
                panels = mutableListOf<Panel>().apply {
                    val array = value.optJSONArray("panels") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { add(Panel.from(it)) }
                    }
                },
                stage = value.optString("stage", "copy"),
                status = value.optString("status", "draft"),
                detail = value.optString("detail"),
                storyboardConfirmed = value.optBoolean("storyboard_confirmed"),
                storyboardSource = value.optString("storyboard_source"),
                referenceSupported = value.optBoolean("reference_supported", true),
                taskStartedAt = value.optLong("task_started_at"),
                completed = value.optInt("completed"),
                total = value.optInt("total"),
            )
        }
    }
}

private fun copyJson(copy: CopyDocument) = JSONObject()
    .put("title", copy.title)
    .put("cover_line_1", copy.coverLine1)
    .put("cover_line_2", copy.coverLine2)
    .put("body", copy.body)
    .put("tags", copy.tags)

private fun copyFrom(value: JSONObject) = CopyDocument(
    title = value.optString("title"),
    coverLine1 = value.optString("cover_line_1"),
    coverLine2 = value.optString("cover_line_2"),
    body = value.optString("body"),
    tags = value.optString("tags"),
)

private fun characterJson(character: CharacterLock) = JSONObject()
    .put("id", character.id)
    .put("name", character.name)
    .put("role", character.role)
    .put("appearance", character.appearance)

private fun characterFrom(value: JSONObject) = CharacterLock(
    id = value.optString("id"),
    name = value.optString("name"),
    role = value.optString("role"),
    appearance = value.optString("appearance"),
)

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun create(): ComicProject = synchronized(this) {
        val project = ComicProject(UUID.randomUUID().toString(), "未命名图集")
        save(project)
        project
    }

    fun get(id: String): ComicProject? = synchronized(this) {
        val file = File(directory(id), "project.json")
        if (!file.isFile) null else runCatching { ComicProject.from(JSONObject(file.readText())) }.getOrNull()
    }

    fun all(): List<ComicProject> = synchronized(this) {
        root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching { get(dir.name) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun save(project: ComicProject) = synchronized(this) {
        val dir = directory(project.id).apply { mkdirs() }
        File(dir, "raw").mkdirs()
        File(dir, "final").mkdirs()
        val pending = File(dir, "project.json.tmp")
        pending.writeText(project.json().toString(2))
        Files.move(pending.toPath(), File(dir, "project.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun delete(id: String) = synchronized(this) {
        directory(id).deleteRecursively()
    }

    fun clearImages(project: ComicProject) = synchronized(this) {
        File(directory(project.id), "raw").deleteRecursively()
        File(directory(project.id), "final").deleteRecursively()
        referenceFile(project).delete()
    }

    fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "项目编号无效" }
        return File(root, id)
    }

    fun rawFile(project: ComicProject, panel: Panel): File {
        val name = if (panel.kind == "cover") "00.png" else "%02d.png".format(pageNumber(project, panel))
        return File(directory(project.id), "raw/$name")
    }

    fun finalFile(project: ComicProject, panel: Panel): File {
        val name = if (panel.kind == "cover") ExportNames.cover() else ExportNames.page(pageNumber(project, panel))
        return File(directory(project.id), "final/$name")
    }

    fun referenceFile(project: ComicProject) = File(directory(project.id), "reference.png")

    fun copyFile(project: ComicProject) = File(directory(project.id), "final/${ExportNames.COPY}")

    fun pageNumber(project: ComicProject, panel: Panel) =
        StoryboardRules.pageNumber(project.panels.map { it.spec() }, panel.spec())
}
