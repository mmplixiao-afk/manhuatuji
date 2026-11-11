package com.manhuatuji.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.manhuatuji.core.CopyDocument
import com.manhuatuji.core.ExportNames
import com.manhuatuji.core.StoryboardRules
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SetExporter(private val context: Context, private val store: ProjectStore) {
    fun files(project: ComicProject): List<Pair<String, File>> {
        val manuscript = project.copy?.format()
            ?: CopyDocument.tryExport(project.rawCopy)
            ?: StoryboardRules.normalizeManuscript(project.rawCopy)
        require(manuscript.isNotBlank()) { "没有可导出的文稿" }
        val pages = project.panels.filter { it.kind == "page" }
        require(pages.isNotEmpty()) { "没有内页" }
        val out = mutableListOf<Pair<String, File>>()
        val cover = project.panels.firstOrNull { it.kind == "cover" }
        if (cover != null) {
            val coverFile = store.finalFile(project, cover)
            require(coverFile.isFile && coverFile.length() > 1000) { "封面还没有生成" }
            out += ExportNames.cover() to coverFile
        }
        pages.forEachIndexed { index, panel ->
            val file = store.finalFile(project, panel)
            val name = ExportNames.page(index + 1)
            require(file.isFile && file.length() > 1000) { "第 ${index + 1} 页还没有生成" }
            out += name to file
        }
        val text = store.copyFile(project)
        text.parentFile?.mkdirs()
        text.writeText(manuscript)
        out += ExportNames.COPY to text
        return out
    }

    fun zip(project: ComicProject): File {
        val entries = files(project)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zip = File(dir, safe(project.title) + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { stream ->
            entries.forEach { (name, file) ->
                stream.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(stream) }
                stream.closeEntry()
            }
        }
        return zip
    }

    fun saveToAlbum(project: ComicProject): String {
        val entries = files(project)
        val resolver = context.contentResolver
        val folder = "${safe(project.title)}_${project.id.take(8)}"
        entries.filter { it.first.endsWith(".png") }.forEach { (name, file) ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/漫画图集/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在相册创建 $name")
            try {
                resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入 $name")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
        val text = entries.first { it.first == ExportNames.COPY }.second
        val textValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ExportNames.COPY)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/漫画图集/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val textUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, textValues)
        if (textUri != null) {
            resolver.openOutputStream(textUri)?.use { output -> text.inputStream().use { it.copyTo(output) } }
            resolver.update(textUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return "已保存到相册 Pictures/漫画图集/$folder"
    }

    fun writeToTree(project: ComicProject, tree: Uri): String {
        val entries = files(project)
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("无法打开所选文件夹")
        val dir = root.createDirectory(safe(project.title) + "_" + project.id.take(8)) ?: root
        entries.forEach { (name, file) ->
            val mime = if (name.endsWith(".png")) "image/png" else "text/plain"
            val existing = dir.findFile(name)
            existing?.delete()
            val doc = dir.createFile(mime, name) ?: error("无法创建 $name")
            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("无法写入 $name")
        }
        return "已导出 ${entries.size} 个文件到所选文件夹"
    }

    private fun safe(title: String) = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(40).ifBlank { "图集" }
}
