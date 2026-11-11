package com.manhuatuji.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.manhuatuji.core.CopyDocument
import com.manhuatuji.core.ImageApiSpec
import com.manhuatuji.core.ImageRetry
import com.manhuatuji.core.PromptBuilder
import com.manhuatuji.core.RetryDecision
import com.manhuatuji.core.StoryboardRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: ProjectStore
    private lateinit var settings: AppSettings
    private lateinit var compositor: ImageCompositor
    private val api = CloudApi()
    private var job: Job? = null
    private var activeProject = ""
    private var taskRunning = false
    private val taskLock = Any()

    override fun onCreate() {
        super.onCreate()
        store = ProjectStore(this)
        settings = AppSettings(this)
        compositor = ImageCompositor(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "图集生成", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示分镜和出图进度"
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            synchronized(taskLock) {
                taskRunning = false
                store.get(activeProject)?.let { project ->
                    project.status = "paused"
                    project.detail = "已停止，已完成的图片会保留"
                    store.save(project)
                    notifyStatus(project.detail, false)
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra(EXTRA_PROJECT) ?: return START_NOT_STICKY
        val panelId = intent.getIntExtra(EXTRA_PANEL, -1)
        val feedback = intent.getStringExtra(EXTRA_FEEDBACK).orEmpty()
        activeProject = id
        val notification = notification("正在准备图集")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        synchronized(taskLock) { taskRunning = true }
        job = scope.launch {
            val project = store.get(id) ?: return@launch
            try {
                project.taskStartedAt = System.currentTimeMillis()
                project.status = "running"
                store.save(project)
                when (intent.action) {
                    ACTION_STORYBOARD -> generateStoryboard(project, feedback)
                    ACTION_IMAGES -> generateImages(project)
                    ACTION_REGENERATE -> regenerate(project, panelId)
                    else -> error("未知任务")
                }
            } catch (cancel: CancellationException) {
                project.status = "paused"
                project.detail = "已停止，已完成的图片会保留"
                store.save(project)
                notifyStatus(project.detail, false)
            } catch (error: Exception) {
                project.status = "failed"
                project.detail = (error.message ?: "任务失败").take(220)
                store.save(project)
                notifyStatus(project.detail, false)
            } finally {
                synchronized(taskLock) { taskRunning = false }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun generateStoryboard(project: ComicProject, feedback: String) {
        val manuscript = StoryboardRules.normalizeManuscript(project.rawCopy)
        require(StoryboardRules.canSplit(manuscript)) { "请先粘贴文稿" }
        project.rawCopy = manuscript
        runCatching { CopyDocument.parse(manuscript) }.getOrNull()?.let { structured ->
            project.copy = structured
            project.title = structured.title
            if (project.coverLine1.isBlank()) project.coverLine1 = structured.coverLine1
            if (project.coverLine2.isBlank()) project.coverLine2 = structured.coverLine2
        }
        if (project.title.isBlank() || project.title == "未命名图集") {
            project.title = manuscript.lineSequence().firstOrNull { it.isNotBlank() }?.take(18) ?: "未命名图集"
        }
        project.storyboardConfirmed = false
        update(project, "storyboard", "正在生成分镜")
        val prompt = settings.prompt("storyboard")
        val note = feedback.trim()
        var last = ""
        var parsed: com.manhuatuji.core.Storyboard? = null
        for (attempt in 0..1) {
            val extra = buildString {
                if (note.isNotEmpty()) {
                    append("\n\n【修改意见】\n")
                    append(note)
                    append("\n请按修改意见重新分镜。")
                }
                if (attempt > 0) {
                    append("\n\n上次结果不合格：")
                    append(last)
                    append("\n请只输出 JSON。")
                }
            }
            val content = api.text(
                settings.textBaseUrl,
                settings.textModel,
                settings.apiKey,
                prompt + extra,
                manuscript,
            )
            val attemptParse = runCatching { StoryboardRules.parse(content) }
            parsed = attemptParse.getOrNull()
            if (parsed == null) {
                last = attemptParse.exceptionOrNull()?.message ?: "分镜不是 JSON"
                if (attempt == 0) update(project, "storyboard", "分镜格式不对，正在重试一次")
                continue
            }
            applyBoard(project, parsed, manuscript)
            val warnings = StoryboardRules.warnings(parsed)
            val blocking = StoryboardRules.blockingIssues(manuscript, parsed)
            project.stage = "storyboard"
            project.status = "review"
            project.detail = when {
                blocking.isNotEmpty() -> "分镜已生成，确认出图前还需处理：" + blocking.take(3).joinToString("；")
                warnings.isEmpty() -> "分镜已生成，请检查。要改节奏就写反馈后重拆，满意后再确认出图"
                else -> "分镜已生成。" + warnings.take(3).joinToString("；")
            }
            store.save(project)
            notifyStatus(project.detail, false)
            return
        }
        error("分镜重试一次后仍无法解析：$last")
    }

    private fun applyBoard(project: ComicProject, board: com.manhuatuji.core.Storyboard, manuscript: String) {
        store.clearImages(project)
        project.characters = board.characters.toMutableList()
        project.panels = board.panels.map {
            Panel(it.id, it.kind, it.caption, it.scene, it.imagePrompt, it.characterIds)
        }.toMutableList()
        project.storyboardConfirmed = false
        project.storyboardSource = manuscript
        project.referenceSupported = true
        project.completed = 0
        project.total = project.panels.size
    }

    private suspend fun generateImages(project: ComicProject) {
        StoryboardRules.requireConfirmed(project.storyboardConfirmed)
        val manuscript = StoryboardRules.normalizeManuscript(project.rawCopy)
        StoryboardRules.validateForImages(manuscript, project.storyboard())
        ensureCover(project)
        val order = StoryboardRules.drawOrder(project.storyboard()).filter { panel ->
            panel.kind != "cover" || (project.coverLine1.isNotBlank() && project.coverLine2.isNotBlank())
        }
        project.total = order.size
        var done = project.panels.count { it.imageStatus == "done" }
        for (spec in order) {
            val panel = project.panels.first { it.id == spec.id }
            if (panel.imageStatus == "done" && store.finalFile(project, panel).length() > 1000) continue
            update(project, "images", "正在绘制${label(project, panel)}", done, order.size)
            val ok = drawPanel(project, panel)
            val latest = project.panels.first { it.id == panel.id }
            if (!ok) {
                project.status = "failed"
                project.stage = "images"
                project.detail = "${label(project, latest)}失败，已停止。同一张最多重试 ${ImageApiSpec.MAX_RETRIES} 次。${latest.error}"
                store.save(project)
                notifyStatus(project.detail, false)
                return
            }
            done++
            update(project, "images", "已完成 $done/${order.size}", done, order.size)
        }
        project.stage = "done"
        project.status = "done"
        project.completed = order.size
        project.detail = "图集已生成，可以预览和导出"
        store.save(project)
        notifyStatus(project.detail, false)
    }

    private suspend fun regenerate(project: ComicProject, panelId: Int) {
        StoryboardRules.requireConfirmed(project.storyboardConfirmed)
        val panel = project.panels.firstOrNull { it.id == panelId } ?: error("找不到这一张")
        if (panel.kind == "cover" && (project.coverLine1.isBlank() || project.coverLine2.isBlank())) {
            error("请先填写封面两行")
        }
        update(project, "images", "正在重做${label(project, panel)}")
        val ok = drawPanel(project, panel)
        val latest = project.panels.first { it.id == panel.id }
        if (!ok) {
            project.status = "failed"
            project.detail = "${label(project, latest)}重做失败。同一张最多重试 ${ImageApiSpec.MAX_RETRIES} 次。${latest.error}"
        } else if (project.panels.all { it.imageStatus == "done" }) {
            project.stage = "done"
            project.status = "done"
            project.detail = "这一张已更新"
        } else {
            project.status = "review"
            project.stage = "images"
            project.detail = "${label(project, panel)}已更新"
        }
        store.save(project)
        notifyStatus(project.detail, false)
    }

    private fun ensureCover(project: ComicProject) {
        if (project.coverLine1.isBlank() || project.coverLine2.isBlank()) return
        if (project.panels.any { it.kind == "cover" }) return
        val anchor = project.panels.firstOrNull { it.kind == "page" } ?: return
        val id = (project.panels.maxOfOrNull { it.id } ?: 0) + 1
        project.panels.add(
            0,
            Panel(
                id = id,
                kind = "cover",
                caption = "",
                scene = anchor.scene,
                imagePrompt = anchor.imagePrompt,
                characterIds = anchor.characterIds,
            ),
        )
        store.save(project)
    }

    private suspend fun drawPanel(project: ComicProject, panel: Panel): Boolean {
        var retriesUsed = 0
        var prompt = panel.imagePrompt
        var allowReference = project.referenceSupported
        val anchor = project.panels.firstOrNull { it.kind == "page" }
        while (true) {
            val index = project.panels.indexOfFirst { it.id == panel.id }
            val current = panel.copy(imagePrompt = prompt, imageStatus = "running", retries = retriesUsed, error = "")
            project.panels[index] = current
            store.save(project)
            val sharesCast = anchor != null && panel.characterIds.any { it in anchor.characterIds }
            val useReference = allowReference && sharesCast && panel.id != anchor?.id &&
                store.referenceFile(project).length() > 1000
            try {
                val finalPrompt = PromptBuilder.imagePrompt(
                    current.spec(),
                    project.characters,
                    settings.prompt("image"),
                )
                val raw = store.rawFile(project, current)
                api.image(settings.imageProvider(), finalPrompt, raw, if (useReference) referenceBase64(project) else null)
                val bitmap = BitmapFactory.decodeFile(raw.path) ?: error("图片无法解码")
                val composed = if (current.kind == "cover") {
                    compositor.composeCover(bitmap, project.coverLine1, project.coverLine2)
                } else {
                    compositor.composePage(bitmap, current.caption)
                }
                bitmap.recycle()
                compositor.save(composed, store.finalFile(project, current))
                composed.recycle()
                if (anchor != null && panel.id == anchor.id) {
                    Files.copy(raw.toPath(), store.referenceFile(project).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                project.panels[index] = current.copy(imageStatus = "done", error = "", retries = retriesUsed)
                project.referenceSupported = allowReference
                store.save(project)
                return true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (useReference && ImageRetry.isUnsupportedReference(message)) {
                    allowReference = false
                    project.referenceSupported = false
                    store.save(project)
                    continue
                }
                when (val decision = ImageRetry.decide(message, retriesUsed)) {
                    RetryDecision.Stop -> {
                        project.panels[index] = current.copy(imageStatus = "failed", retries = retriesUsed, error = message.take(180))
                        store.save(project)
                        return false
                    }
                    RetryDecision.RetrySame -> {
                        retriesUsed++
                        delay(1_500L * retriesUsed)
                    }
                    RetryDecision.Rewrite -> {
                        retriesUsed++
                        prompt = rewritePrompt(project, current.copy(imagePrompt = prompt), message)
                        delay(1_000L)
                    }
                }
            }
        }
    }

    private fun rewritePrompt(project: ComicProject, panel: Panel, errorMessage: String): String {
        val cast = project.characters.joinToString("\n") { "${it.name}（${it.role}）：${it.appearance}" }
        val input = """
            原提示词：
            ${panel.imagePrompt}

            画面说明：
            ${panel.scene}

            审核或接口报错：
            $errorMessage

            必须保留的人物外貌：
            $cast
        """.trimIndent()
        val rewritten = api.text(
            settings.textBaseUrl,
            settings.textModel,
            settings.apiKey,
            settings.prompt("image_failure"),
            input,
        ).trim().trim('`').take(800)
        require(rewritten.isNotBlank()) { "改写后的提示词是空的" }
        val index = project.panels.indexOfFirst { it.id == panel.id }
        if (index >= 0) project.panels[index] = project.panels[index].copy(imagePrompt = rewritten)
        return rewritten
    }

    private fun referenceBase64(project: ComicProject): String? {
        val file = store.referenceFile(project)
        if (!file.isFile || file.length() !in 1_000..3_500_000) return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun label(project: ComicProject, panel: Panel) =
        if (panel.kind == "cover") "封面" else "第 ${store.pageNumber(project, panel)} 页"

    private fun update(project: ComicProject, stage: String, detail: String, completed: Int = project.completed, total: Int = project.total) {
        project.stage = stage
        project.status = "running"
        project.detail = detail
        project.completed = completed
        project.total = total
        store.save(project)
        notifyStatus(detail, true)
    }

    private fun notification(detail: String, active: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, GenerationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (active) "正在生成图集" else "图集任务")
            .setContentText(detail)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
        if (active) builder.addAction(android.R.drawable.ic_media_pause, "停止", pause)
        return builder.build()
    }

    private fun notifyStatus(detail: String, active: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail, active))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "project_id"
        const val EXTRA_PANEL = "panel_id"
        const val EXTRA_FEEDBACK = "feedback"
        const val ACTION_STORYBOARD = "storyboard"
        const val ACTION_IMAGES = "images"
        const val ACTION_REGENERATE = "regenerate"
        const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "manhuatuji_generation"
        private const val NOTIFICATION_ID = 41
    }
}
