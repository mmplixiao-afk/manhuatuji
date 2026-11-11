package com.manhuatuji.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.manhuatuji.core.CharacterLock
import com.manhuatuji.core.CopyDocument
import com.manhuatuji.core.ImageApiSpec
import com.manhuatuji.core.ModelCatalog
import com.manhuatuji.core.Storyboard
import com.manhuatuji.core.StoryboardRules
import com.manhuatuji.core.TextModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(store: ProjectStore, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ComicProject?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            tick++
        }
    }
    val projects = remember(tick) { store.all() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("漫画图集", modifier = Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Ink) }
        }
        Text("粘贴文案，先确认分镜，再出图。字和封面红圈由程序叠上。", color = Muted)
        Button(
            onClick = { onOpen(store.create().id) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("新建图集", modifier = Modifier.padding(start = 6.dp))
        }
        if (projects.isEmpty()) {
            Text("还没有图集", color = Muted, modifier = Modifier.padding(top = 24.dp))
        }
        projects.forEach { project ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(project.title.ifBlank { "未命名图集" }, fontWeight = FontWeight.Bold, color = Ink)
                    Text(stageLabel(project), color = if (project.status == "failed") Accent else Muted, fontSize = 13.sp)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.createdAt)),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpen(project.id) }, shape = RoundedCornerShape(10.dp)) { Text("打开") }
                        if (project.status != "running") {
                            TextButton(onClick = { pendingDelete = project }) { Text("删除", color = Accent) }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个图集？") },
            text = { Text(project.title) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(project.id)
                    pendingDelete = null
                    tick++
                }) { Text("删除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
fun ProjectScreen(
    store: ProjectStore,
    projectId: String,
    banner: String,
    onBanner: (String) -> Unit,
    onBack: () -> Unit,
    onPreview: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(projectId) { store.get(projectId) }
    if (initial == null) {
        Text("找不到这个图集")
        return
    }
    var raw by remember(projectId) { mutableStateOf(initial.rawCopy) }
    var title by remember(projectId) { mutableStateOf(initial.title) }
    var cover1 by remember(projectId) { mutableStateOf(initial.coverLine1) }
    var cover2 by remember(projectId) { mutableStateOf(initial.coverLine2) }
    var feedback by remember(projectId) { mutableStateOf("") }
    val characters = remember(projectId) { initial.characters.toMutableStateList() }
    val panels = remember(projectId) { initial.panels.toMutableStateList() }
    var status by remember(projectId) { mutableStateOf(initial.status) }
    var detail by remember(projectId) { mutableStateOf(initial.detail) }
    var confirmed by remember(projectId) { mutableStateOf(initial.storyboardConfirmed) }
    var source by remember(projectId) { mutableStateOf(initial.storyboardSource) }
    var completed by remember(projectId) { mutableIntStateOf(initial.completed) }
    var total by remember(projectId) { mutableIntStateOf(initial.total) }
    var awaiting by remember(projectId) { mutableStateOf(false) }
    var tick by remember(projectId) { mutableIntStateOf(0) }
    LaunchedEffect(projectId) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    LaunchedEffect(tick) {
        val fresh = store.get(projectId) ?: return@LaunchedEffect
        status = fresh.status
        detail = fresh.detail
        confirmed = fresh.storyboardConfirmed
        source = fresh.storyboardSource
        completed = fresh.completed
        total = fresh.total
        if (awaiting && fresh.status != "running") {
            characters.clear()
            characters.addAll(fresh.characters)
            panels.clear()
            panels.addAll(fresh.panels)
            title = fresh.title
            if (cover1.isBlank()) cover1 = fresh.coverLine1
            if (cover2.isBlank()) cover2 = fresh.coverLine2
            awaiting = false
        } else if (!awaiting) {
            mergePanelStatus(panels, fresh.panels)
        }
    }
    val canSplit = StoryboardRules.canSplit(raw)
    val storyboardNow = Storyboard(characters.toList(), panels.map { it.spec() })
    val blocking = if (panels.isEmpty()) emptyList() else StoryboardRules.blockingIssues(raw, storyboardNow)
    val warnings = if (panels.isEmpty()) emptyList() else StoryboardRules.warnings(storyboardNow)
    val copyDirty = source.isNotBlank() &&
        StoryboardRules.normalizeManuscript(raw) != StoryboardRules.normalizeManuscript(source)
    val busy = status == "running"
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { SetExporter(context, store).writeToTree(store.get(projectId)!!, uri) }.getOrElse { it.message ?: "导出失败" }
            }
            onBanner(message)
        }
    }

    fun edited(): ComicProject? {
        val base = store.get(projectId) ?: return null
        base.rawCopy = raw
        base.coverLine1 = cover1
        base.coverLine2 = cover2
        base.title = title
        base.characters = characters.toMutableList()
        base.panels = panels.map { it.copy() }.toMutableList()
        runCatching { CopyDocument.parse(raw) }.getOrNull()?.let { structured ->
            base.copy = structured
            if (base.title.isBlank() || base.title == "未命名图集") base.title = structured.title
        }
        return base
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text(title.ifBlank { "未命名图集" }, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
        }
        if (banner.isNotBlank()) Text(banner, color = Accent)
        if (detail.isNotBlank()) Text(detail, color = if (status == "failed") Accent else Muted)
        if (busy && total > 0) {
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat().coerceAtLeast(1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
            )
        }
        if (busy) {
            TextButton(onClick = {
                context.startService(Intent(context, GenerationService::class.java).setAction(GenerationService.ACTION_CANCEL))
            }) { Text("停止", color = Accent) }
        }
        Text("文稿", fontWeight = FontWeight.Bold, color = Ink)
        Text("直接粘贴豆包原稿或记录。生成分镜不会出图。", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            enabled = !busy,
            placeholder = { Text("把一整篇原稿粘在这里，不需要标题、封面字或标签。", color = Muted, fontSize = 13.sp) },
        )
        Button(
            onClick = {
                val project = edited() ?: return@Button
                project.storyboardConfirmed = false
                project.stage = "storyboard"
                project.status = "running"
                project.detail = "正在生成分镜"
                store.save(project)
                awaiting = true
                status = "running"
                startGeneration(context, projectId, GenerationService.ACTION_STORYBOARD)
            },
            enabled = !busy && canSplit,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("生成分镜") }
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("分镜反馈") },
            placeholder = { Text("例如：太少、不连贯、开头再短一点", color = Muted) },
        )
        OutlinedButton(
            onClick = {
                val project = edited() ?: return@OutlinedButton
                project.storyboardConfirmed = false
                project.stage = "storyboard"
                project.status = "running"
                project.detail = "正在按反馈重新分镜"
                store.save(project)
                awaiting = true
                status = "running"
                startGeneration(context, projectId, GenerationService.ACTION_STORYBOARD, feedback = feedback)
            },
            enabled = !busy && canSplit && feedback.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("按反馈重新分镜") }
        Text("封面字", fontWeight = FontWeight.Bold, color = Ink)
        Text("两行都可以先空着。填写后再确认出图，第二行会画红圈。", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            value = cover1,
            onValueChange = { cover1 = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("封面第一行") },
        )
        OutlinedTextField(
            value = cover2,
            onValueChange = { cover2 = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("封面第二行（红圈）") },
        )

        if (characters.isNotEmpty()) {
            Text("人物锁定", fontWeight = FontWeight.Bold)
            characters.forEachIndexed { index, character ->
                Card(colors = CardDefaults.cardColors(containerColor = CardWhite), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${character.name} · ${character.role}", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = character.role,
                            onValueChange = { characters[index] = character.copy(role = it) },
                            label = { Text("身份") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = character.appearance,
                            onValueChange = { characters[index] = character.copy(appearance = it) },
                            label = { Text("外貌，之后每张图都用这段") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (panels.isNotEmpty()) {
            Text("分镜", fontWeight = FontWeight.Bold)
            if (copyDirty) Text("文稿已改，请重新生成分镜后再出图。", color = Accent)
            panels.forEachIndexed { index, panel ->
                PanelCard(
                    projectId = projectId,
                    store = store,
                    panel = panel,
                    busy = busy,
                    confirmed = confirmed,
                    refresh = tick,
                    onChange = { panels[index] = it },
                    onDelete = if (panel.kind == "page" && !busy) {
                        { panels.removeAt(index) }
                    } else null,
                    onPreview = { onPreview(panel.id) },
                    onRedraw = {
                        if (copyDirty) {
                            onBanner("文稿已改，请重新生成分镜")
                            return@PanelCard
                        }
                        val project = edited() ?: return@PanelCard
                        if (!project.storyboardConfirmed) {
                            onBanner("请先点「确认出图」")
                            return@PanelCard
                        }
                        store.save(project)
                        startGeneration(context, projectId, GenerationService.ACTION_REGENERATE, panel.id)
                    },
                )
            }
            warnings.forEach { Text(it, color = Muted, fontSize = 13.sp) }
            blocking.forEach { Text(it, color = Accent, fontSize = 13.sp) }
            if (!confirmed) {
                Button(
                    onClick = {
                        val issues = StoryboardRules.blockingIssues(raw, Storyboard(characters.toList(), panels.map { it.spec() }))
                        if (issues.isNotEmpty() || copyDirty) {
                            onBanner(if (copyDirty) "文稿已改，请重新生成分镜" else issues.joinToString("；"))
                            return@Button
                        }
                        val project = edited() ?: return@Button
                        project.storyboardConfirmed = true
                        project.stage = "images"
                        project.status = "running"
                        project.detail = "已确认分镜，开始出图"
                        store.save(project)
                        confirmed = true
                        status = "running"
                        startGeneration(context, projectId, GenerationService.ACTION_IMAGES)
                    },
                    enabled = !busy && blocking.isEmpty() && !copyDirty,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("确认出图") }
            } else if (panels.any { it.imageStatus != "done" }) {
                Button(
                    onClick = {
                        if (copyDirty) {
                            onBanner("文稿已改，请重新生成分镜")
                            return@Button
                        }
                        val project = edited() ?: return@Button
                        project.storyboardConfirmed = true
                        store.save(project)
                        startGeneration(context, projectId, GenerationService.ACTION_IMAGES)
                    },
                    enabled = !busy && !copyDirty,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("继续出未完成的图") }
            }
            if (panels.any { it.imageStatus == "done" }) {
                Text("导出", fontWeight = FontWeight.Bold)
                Text("封面 00_封面.png，内页 01.png 起，外加 文案.txt。", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            onBanner(withContext(Dispatchers.IO) {
                                runCatching { SetExporter(context, store).saveToAlbum(store.get(projectId)!!) }.getOrElse { it.message ?: "保存失败" }
                            })
                        }
                    }, enabled = !busy && !copyDirty) { Text("保存到相册") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val zipped = withContext(Dispatchers.IO) {
                                runCatching { SetExporter(context, store).zip(store.get(projectId)!!) }
                            }
                            zipped.onSuccess { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND)
                                    .setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(share, "分享图集"))
                            }.onFailure { onBanner(it.message ?: "分享失败") }
                        }
                    }, enabled = !busy && !copyDirty) { Text("分享压缩包") }
                }
                OutlinedButton(onClick = { treeLauncher.launch(null) }, enabled = !busy && !copyDirty, modifier = Modifier.fillMaxWidth()) {
                    Text("导出到文件夹")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PanelCard(
    projectId: String,
    store: ProjectStore,
    panel: Panel,
    busy: Boolean,
    confirmed: Boolean,
    refresh: Int,
    onChange: (Panel) -> Unit,
    onDelete: (() -> Unit)?,
    onPreview: () -> Unit,
    onRedraw: () -> Unit,
) {
    val project = store.get(projectId)
    val file = project?.let { store.finalFile(it, panel) }
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (panel.kind == "cover") "封面" else "内页", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(panelStatus(panel), color = if (panel.imageStatus == "failed") Accent else Muted, fontSize = 12.sp)
            }
            if (file != null) Thumb(file, refresh, Modifier.fillMaxWidth().aspectRatio(3f / 4f), onPreview)
            if (panel.kind == "page") {
                OutlinedTextField(
                    value = panel.caption,
                    onValueChange = { onChange(panel.copy(caption = it)) },
                    label = { Text("旁白原文") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("封面两行大字和红圈用文案里的封面字，不让模型写字。", color = Muted, fontSize = 13.sp)
            }
            OutlinedTextField(
                value = panel.scene,
                onValueChange = { onChange(panel.copy(scene = it)) },
                label = { Text("画面") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = panel.imagePrompt,
                onValueChange = { onChange(panel.copy(imagePrompt = it)) },
                label = { Text("生图提示词，不要写要叠的字") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (panel.error.isNotBlank()) Text(panel.error, color = Accent, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmed) {
                    OutlinedButton(onClick = onRedraw, enabled = !busy) { Text("重做此张") }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除此页", color = Accent) }
            }
        }
    }
}

@Composable
fun PreviewScreen(store: ProjectStore, projectId: String, panelId: Int, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val project = store.get(projectId)
    val panel = project?.panels?.firstOrNull { it.id == panelId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text(if (panel?.kind == "cover") "封面预览" else "内页预览", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (project == null || panel == null) {
            Text("这张图不在了")
            return
        }
        val file = store.finalFile(project, panel)
        Thumb(file, file.lastModified().toInt(), Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {}
        if (project.storyboardConfirmed) {
            Button(
                onClick = {
                    if (project.status == "running") {
                        onMessage("正在出图，请等这一轮结束")
                        return@Button
                    }
                    startGeneration(context, projectId, GenerationService.ACTION_REGENERATE, panel.id)
                    onMessage("开始重做这一张")
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("重做此张") }
        }
    }
}

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, onPrompts: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("text") }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(settings.textBaseUrl) }
    var model by remember { mutableStateOf(settings.textModel) }
    val savedImage = remember { settings.imageProvider() }
    var imageName by remember { mutableStateOf(savedImage.name) }
    var imageUrl by remember { mutableStateOf(savedImage.baseUrl) }
    var imageModel by remember { mutableStateOf(savedImage.model) }
    var imageKey by remember { mutableStateOf("") }
    var imageSize by remember { mutableStateOf(savedImage.imageSize) }
    var imageKeySaved by remember { mutableStateOf(settings.hasImageApiKey()) }
    var testing by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    var showRest by remember { mutableStateOf(false) }
    val busy = testing || listing
    fun probe(kind: String) {
        val apiKey = if (kind == "text") {
            key.trim().ifBlank { settings.apiKey }
        } else {
            imageKey.trim().ifBlank { settings.imageProvider().key }
        }
        testing = true
        connectionResult = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = CloudApi()
                    if (kind == "text") {
                        api.text(
                            url.trim(),
                            model.trim(),
                            apiKey,
                            "你是接口测试助手，只回复连接成功。",
                            "请回复：连接成功",
                        )
                        "文本接口连接成功"
                    } else {
                        val output = File(context.cacheDir, "api-test-image.png")
                        try {
                            api.image(
                                ImageProviderConfig(imageName, imageUrl, imageModel, apiKey, imageSize),
                                "一张简单的红色圆形，白底，无文字、无水印。",
                                output,
                            )
                            "图片接口连接成功"
                        } finally {
                            output.delete()
                        }
                    }
                }.getOrElse { error ->
                    "连接失败：${error.message.orEmpty().take(160).ifBlank { "请检查配置" }}"
                }
            }
            testing = false
            connectionResult = result
            onMessage(result)
        }
    }
    fun loadModels() {
        val apiKey = key.trim().ifBlank { settings.apiKey }
        listing = true
        connectionResult = ""
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { TextModels.arrange(CloudApi().models(url.trim(), apiKey)) }
            }
            listing = false
            loaded.onSuccess { found ->
                catalog = found
                showRest = found.textFirst.isEmpty()
                connectionResult = when {
                    found.isEmpty -> "连接失败：接口没有返回模型"
                    found.onlyImageModels -> "这个 Key 可能没有文本模型，请换一个能对话的 Key。下面仍列出返回的模型。"
                    else -> "已获取 ${found.textFirst.size + found.rest.size} 个模型，点一个填入模型 ID"
                }
            }.onFailure { error ->
                catalog = null
                connectionResult = "连接失败：${error.message.orEmpty().take(160).ifBlank { "请检查配置" }}"
            }
            onMessage(connectionResult)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("模型与接口", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("text" to "文本接口", "image" to "图片接口").forEach { (id, label) ->
                if (tab == id) {
                    Button(onClick = { tab = id }, modifier = Modifier.weight(1f)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { tab = id }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
        if (tab == "text") {
            Text(if (settings.hasApiKey()) "API Key 已加密保存在本机" else "还没有填写 API Key", color = Muted)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                enabled = !busy,
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                url,
                { url = it },
                enabled = !busy,
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("文本模型", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppSettings.textModelChoices.forEach { (id, label) ->
                    OutlinedButton(onClick = { model = id }, enabled = !busy) { Text(label, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(
                model,
                { model = it },
                enabled = !busy,
                label = { Text("模型 ID，也可以直接改") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { loadModels() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (listing) "获取中..." else "获取模型列表") }
            catalog?.let { found ->
                val visible = if (showRest) found.textFirst + found.rest else found.textFirst
                visible.forEach { id ->
                    TextButton(
                        onClick = { model = id },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(id, maxLines = 1) }
                }
                if (found.rest.isNotEmpty() && found.textFirst.isNotEmpty()) {
                    TextButton(onClick = { showRest = !showRest }, enabled = !busy) {
                        Text(if (showRest) "收起其余 ${found.rest.size} 个" else "显示其余 ${found.rest.size} 个")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        settings.textBaseUrl = url
                        settings.textModel = model
                        if (key.isNotBlank()) settings.apiKey = key
                        key = ""
                        onMessage(if (settings.hasApiKey()) "文本接口已保存" else "地址和模型已保存，还没有 Key")
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("保存设置") }
                OutlinedButton(
                    onClick = { probe("text") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (testing) "测试中..." else "测试连接") }
            }
        } else {
            Text(if (imageKeySaved) "生图 Key 已加密保存在本机，和文本 Key 分开" else "生图 Key 还没填写，和文本 Key 不是同一把", color = Muted)
            OutlinedTextField(
                imageUrl,
                { imageUrl = it },
                enabled = !busy,
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                imageName,
                { imageName = it },
                enabled = !busy,
                label = { Text("接口类型") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                imageModel,
                { imageModel = it },
                enabled = !busy,
                label = { Text("模型") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = imageKey,
                onValueChange = { imageKey = it },
                enabled = !busy,
                label = { Text("密钥") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("图片尺寸", fontWeight = FontWeight.Bold)
            ImageApiSpec.sizeChoices.forEach { (value, label) ->
                if (imageSize == value) {
                    Button(onClick = { imageSize = value }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { imageSize = value }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
            Text("画面仍由程序叠字。白底和 3:4 是默认尺寸，可以改。", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        settings.saveImageProvider(
                            ImageProviderConfig(imageName, imageUrl, imageModel, imageKey, imageSize),
                        )
                        imageKey = ""
                        imageKeySaved = settings.hasImageApiKey()
                        onMessage(if (imageKeySaved) "图片接口已保存" else "地址和模型已保存，还没有生图 Key")
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("保存设置") }
                OutlinedButton(
                    onClick = { probe("image") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (testing) "测试中..." else "测试连接") }
            }
        }
        if (connectionResult.isNotBlank()) {
            Text(
                connectionResult,
                color = if (connectionResult.startsWith("已获取") || connectionResult.endsWith("连接成功")) Muted else Accent,
            )
        }
        OutlinedButton(onClick = onPrompts, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("分镜和生图提示词") }
    }
}

@Composable
fun PromptScreen(settings: AppSettings, onBack: () -> Unit, onMessage: (String) -> Unit) {
    var selected by remember { mutableStateOf("storyboard") }
    var text by remember(selected) { mutableStateOf(settings.prompt(selected)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("提示词", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("storyboard" to "分镜", "image" to "画面", "image_failure" to "审核改写").forEach { (id, label) ->
                OutlinedButton(onClick = { selected = id }) { Text(label) }
            }
        }
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp))
        Button(onClick = { settings.savePrompt(selected, text); onMessage("提示词已保存") }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        TextButton(onClick = {
            settings.resetPrompt(selected)
            text = settings.prompt(selected)
            onMessage("已恢复默认提示词")
        }) { Text("恢复默认") }
    }
}

@Composable
private fun Thumb(file: File, refresh: Int, modifier: Modifier, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, file.path, file.lastModified(), refresh) {
        value = withContext(Dispatchers.IO) { decodeThumb(file) }
    }
    val current = bitmap
    if (current == null) {
        Spacer(modifier.background(Paper, RoundedCornerShape(8.dp)))
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "预览",
            modifier = modifier.clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun decodeThumb(file: File): Bitmap? {
    if (!file.isFile || file.length() < 100) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 900) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun mergePanelStatus(local: SnapshotStateList<Panel>, fresh: List<Panel>) {
    fresh.forEach { incoming ->
        val index = local.indexOfFirst { it.id == incoming.id }
        if (index < 0) return@forEach
        val old = local[index]
        val promptChangedByRetry = incoming.imagePrompt != old.imagePrompt && incoming.retries != old.retries
        if (old.imageStatus != incoming.imageStatus || old.error != incoming.error || old.retries != incoming.retries || promptChangedByRetry) {
            local[index] = incoming.copy(
                caption = old.caption,
                scene = old.scene,
                imagePrompt = if (promptChangedByRetry) incoming.imagePrompt else old.imagePrompt,
                characterIds = old.characterIds,
            )
        }
    }
}

private fun stageLabel(project: ComicProject): String = when {
    project.status == "running" -> project.detail.ifBlank { "正在生成" }
    project.status == "failed" -> project.detail.ifBlank { "失败" }
    project.status == "done" -> "已完成"
    project.storyboardConfirmed -> "分镜已确认"
    project.panels.isNotEmpty() -> "分镜待确认"
    else -> "待粘贴文案"
}

private fun panelStatus(panel: Panel) = when (panel.imageStatus) {
    "done" -> "已出图"
    "running" -> "正在出图"
    "failed" -> "失败，已重试 ${panel.retries} 次"
    else -> "待出图"
}
