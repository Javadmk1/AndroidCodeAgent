package com.codeagent.android.ui

import com.codeagent.android.SecureKeyStore
import com.codeagent.android.agent.AgentRuntime
import com.codeagent.android.agent.ProjectTransfer
import com.codeagent.android.agent.ToolRegistry
import com.codeagent.android.agent.WorkspaceTools
import com.codeagent.android.llm.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.net.Uri
import android.provider.OpenableColumns

private data class LiveLine(val role: String, val text: String)
private enum class MainTab { CHAT, PROJECTS, HISTORY, SETTINGS }

private const val AGENT_SYSTEM_PROMPT = """
You are an Android coding assistant working only inside the user's imported project workspace.
Your available project tools are list_files, read_file, search_in_files, and write_file.
Start by inspecting the project structure and relevant source files before proposing changes.
Use the tools when file inspection or editing is needed; do not merely describe edits that you can perform.
Never overwrite a file without first reading it when it already exists.
The write_file tool requires explicit user approval; explain the intended change briefly and let the approval dialog decide.
Do not claim that you compiled, ran, tested, installed, or debugged a project: this app currently has no local Gradle build or shell-execution tool.
Do not invent files, test results, build results, API responses, or repository state.
Make focused changes, preserve unrelated code, and explain exactly which files changed and which verification remains.
Do not request or expose API keys, passwords, signing keystores, or other secrets in chat or project files.
If the task needs a build, explain that the current remote build workflow supports public GitHub repositories and that the user must start it from GitHub Actions.
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAgentApp(keys: SecureKeyStore, configs: ProviderConfigStore) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(configs.load()) }
    var provider by remember { mutableStateOf(config.provider) }
    var model by remember { mutableStateOf(config.model) }
    var endpoint by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var pendingImageData by remember { mutableStateOf<String?>(null) }
    var pendingImageMime by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentName by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf(configs.loadProfiles()) }
    var profilesExpanded by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MainTab.CHAT) }
    var activityPanel by remember { mutableStateOf("activity") }
    val agentEvents = remember { mutableStateListOf<String>() }
    var buildStatus by remember { mutableStateOf("هنوز ساختی اجرا نشده است.") }
    var buildLog by remember { mutableStateOf("") }
    var buildOutput by remember { mutableStateOf("هنوز APK خروجی دریافت نشده است.") }
    var showWorkspace by remember { mutableStateOf(false) }
    var showHtmlPreview by remember { mutableStateOf(false) }
    var showBuildManager by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var showConversationList by remember { mutableStateOf(false) }
    var renameConversationId by remember { mutableStateOf<String?>(null) }
    var renameConversationText by remember { mutableStateOf("") }
    var deletingConversationId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var transferBusy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("API هنوز پیکربندی نشده است.") }
    val historyStore = remember(context) { MultiConversationStore(context) }
    val initialConversations = remember(historyStore) { historyStore.loadAll().ifEmpty { listOf(historyStore.create()) } }
    var conversations by remember { mutableStateOf(initialConversations) }
    var activeConversationId by remember { mutableStateOf(initialConversations.first().id) }
    val activeConversationTitle = conversations.firstOrNull { it.id == activeConversationId }?.title ?: "گفتگوی جدید"
    var approvalPath by remember { mutableStateOf<String?>(null) }
    var approvalPreview by remember { mutableStateOf("") }
    var approvalDecision by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { LlmClient() }
    val workspaceDirectory = remember(context) { File(context.filesDir, "workspace") }
    val transfer = remember(context) { ProjectTransfer(context, workspaceDirectory) }
    val runtime = remember(context) {
        AgentRuntime(ToolRegistry(WorkspaceTools(workspaceDirectory)))
    }
    val lines = remember(historyStore, activeConversationId) {
        mutableStateListOf<LiveLine>().apply {
            val selected = conversations.firstOrNull { it.id == activeConversationId }
            addAll(selected?.messages.orEmpty().map { LiveLine(it.role, it.content) })
            if (isEmpty()) add(LiveLine("assistant", "سلام. گفتگوی جدید آماده است؛ درخواستت را بنویس."))
        }
    }
    val htmlPreview = lines.lastOrNull { it.role == "assistant" }?.text?.let { message ->
        Regex("""(?is)```(?:html)?\s*(.*?)```""").find(message)?.groupValues?.get(1)?.trim()
            ?: message.takeIf { it.contains("<html", true) || it.contains("<!doctype html", true) }
    }
    fun persistHistory() {
        historyStore.save(activeConversationId, activeConversationTitle, lines.map { ChatMessage(it.role, it.text) })
        conversations = historyStore.loadAll()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            transferBusy = true
            status = "در حال وارد کردن پروژه..."
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val result = transfer.importTree(uri)
                status = "پروژه با موفقیت جایگزین شد: ${result.files} فایل"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status = "خطای ورود پروژه: ${e.message ?: "نامشخص"}"
            } finally { transferBusy = false }
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "attachment"
                val isImage = mime.startsWith("image/")
                val limit = if (isImage) 4 * 1024 * 1024 else 512 * 1024
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= limit) { "فایل بزرگ‌تر از حد مجاز است." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("خواندن فایل ممکن نشد.")
                }
                if (isImage) {
                    pendingImageData = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    pendingImageMime = mime
                    pendingAttachmentName = name
                    status = "تصویر آماده ارسال است: $name"
                } else if (mime.startsWith("text/") || name.substringAfterLast('.', "").lowercase() in setOf("txt","md","kt","java","html","css","js","json","xml","gradle","py","yml","yaml","csv")) {
                    val textContent = String(bytes, Charsets.UTF_8)
                    prompt = prompt + "\n\nفایل پیوست: $name\n\n$textContent"
                    pendingAttachmentName = name
                    status = "محتوای فایل به پیام اضافه شد: $name"
                } else {
                    status = "نوع فایل پشتیبانی نمی‌شود؛ تصویر یا فایل متنی انتخاب کن."
                }
            } catch (e: Exception) {
                status = "خطای پیوست: ${e.message ?: "نامشخص"}"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            transferBusy = true
            status = "در حال ساخت ZIP پروژه..."
            try {
                val result = transfer.exportZip(uri)
                status = "خروجی پروژه ذخیره شد: ${result.files} فایل"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status = "خطای خروجی پروژه: ${e.message ?: "نامشخص"}"
            } finally { transferBusy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Android Code Agent", style = MaterialTheme.typography.titleMedium)
                        Text(activeConversationTitle + " · " + config.model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "ابزارهای پروژه") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("گفتگوها") }, leadingIcon = { Icon(Icons.Default.Forum, null) }, onClick = { menuExpanded = false; showConversationList = true })
                            DropdownMenuItem(text = { Text("وارد کردن پروژه") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menuExpanded = false; importLauncher.launch(null) })
                            DropdownMenuItem(text = { Text("فایل‌های پروژه") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { menuExpanded = false; showWorkspace = true })
                            DropdownMenuItem(text = { Text("پیش‌نمایش HTML") }, leadingIcon = { Icon(Icons.Default.Code, null) }, enabled = !htmlPreview.isNullOrBlank(), onClick = { menuExpanded = false; showHtmlPreview = true })
                            DropdownMenuItem(text = { Text("ساخت APK") }, leadingIcon = { Icon(Icons.Default.Build, null) }, onClick = { menuExpanded = false; showBuildManager = true })
                            DropdownMenuItem(text = { Text("خروجی ZIP پروژه") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menuExpanded = false; exportLauncher.launch("android-code-agent-project.zip") })
                            DropdownMenuItem(text = { Text("پاک کردن گفتگو") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuExpanded = false; confirmClearHistory = true })
                            DropdownMenuItem(text = { Text("تنظیمات") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menuExpanded = false; provider=config.provider; model=config.model; endpoint=config.baseUrl; apiKey=""; settings=true })
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectedTab == MainTab.CHAT) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(
                    Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (busy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (busy) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Folder, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (busy) "ایجنت در حال کار است" else "Workspace · " + workspaceDirectory.name, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { showWorkspace = true }, label = { Text("فایل‌ها") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) })
                        AssistChip(onClick = { showBuildManager = true }, label = { Text("ساخت APK") }, leadingIcon = { Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp)) })
                        AssistChip(onClick = { showConversationList = true }, label = { Text("گفتگوها") }, leadingIcon = { Icon(Icons.Default.Forum, null, modifier = Modifier.size(16.dp)) })
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { attachmentLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Icon(Icons.Default.AttachFile, "افزودن فایل")
                        }
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("درخواست برنامه‌نویسی...") },
                            maxLines = 5,
                            shape = RoundedCornerShape(22.dp)
                        )
                        FilledIconButton(
                            onClick = {
                                if (busy) {
                                    activeJob?.cancel()
                                    status = "درخواست لغو شد."
                                } else {
                                    val question = prompt.trim()
                                    if (question.isNotEmpty()) {
                                        val secret = keys.read(config.provider)
                                        if (secret.isNullOrBlank()) {
                                            status = "کلید API ثبت نشده است."
                                            settings = true
                                        } else {
                                            prompt = ""
                                            lines.add(LiveLine("user", question))
                                            lines.add(LiveLine("assistant", ""))
                                            persistHistory()
                                            busy = true
                                            agentEvents.add("شروع درخواست: " + question.take(90))
                                            status = "در حال پردازش درخواست..."
                                            val history = lines.dropLast(1).map { ChatMessage(it.role, it.text) }.toMutableList()
                                            val userIndex = history.indexOfLast { it.role == "user" }
                                            if (userIndex >= 0 && pendingImageData != null && pendingImageMime != null) {
                                                history[userIndex] = ChatMessage("user", question + "\n[[IMAGE:" + pendingImageMime + ":" + pendingImageData + "]]")
                                            }
                                            pendingImageData = null
                                            pendingImageMime = null
                                            pendingAttachmentName = null
                                            activeJob = scope.launch {
                                                try {
                                                    val result = runtime.run(
                                                        config = config,
                                                        apiKey = secret,
                                                        conversation = listOf("system" to AGENT_SYSTEM_PROMPT.trimIndent()) + history.map { it.role to it.content },
                                                        onText = { chunk ->
                                                            withContext(Dispatchers.Main.immediate) {
                                                                if (lines.isNotEmpty()) {
                                                                    val current = lines.last()
                                                                    lines[lines.lastIndex] = LiveLine("assistant", current.text + chunk)
                                                                }
                                                            }
                                                        },
                                                        onActivity = { event ->
                                                            withContext(Dispatchers.Main.immediate) {
                                                                agentEvents.add(event)
                                                                if (agentEvents.size > 80) agentEvents.removeAt(0)
                                                            }
                                                        },
                                                        approveWrite = { path, content ->
                                                            val gate = CompletableDeferred<Boolean>()
                                                            approvalPath = path
                                                            approvalPreview = content.take(800)
                                                            approvalDecision = gate
                                                            try { gate.await() } finally {
                                                                approvalPath = null
                                                                approvalPreview = ""
                                                                approvalDecision = null
                                                            }
                                                        }
                                                    )
                                                    val toolSummary = if (result.toolLog.isEmpty()) "" else "\n\nگزارش ابزارها:\n" + result.toolLog.joinToString("\n")
                                                    result.toolLog.forEach { agentEvents.add(it) }
                                                    agentEvents.add("پاسخ ایجنت دریافت و ذخیره شد.")
                                                    lines[lines.lastIndex] = LiveLine("assistant", result.answer + toolSummary)
                                                    persistHistory()
                                                    status = "درخواست به پایان رسید."
                                                } catch (e: CancellationException) {
                                                    persistHistory()
                                                    status = "درخواست لغو شد."
                                                } catch (e: Exception) {
                                                    lines[lines.lastIndex] = LiveLine("assistant", "خطا: " + (e.message ?: "نامشخص"))
                                                    persistHistory()
                                                    agentEvents.add("خطا: " + (e.message ?: "نامشخص"))
                                                    status = "درخواست ناموفق بود؛ مدل، کلید، endpoint و پشتیبانی ابزارها را بررسی کن."
                                                } finally {
                                                    busy = false
                                                    activeJob = null
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = (prompt.isNotBlank() && !busy) || busy
                        ) {
                            if (busy) Icon(Icons.Default.Close, "لغو درخواست")
                            else Icon(Icons.Default.Send, "ارسال")
                        }
                    }
                    if (pendingAttachmentName != null) {
                        Text("پیوست آماده: $pendingAttachmentName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                MainTab.CHAT -> {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(lines) { index, line ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=if(line.role=="user") Arrangement.End else Arrangement.Start) {
                                Surface(color=if(line.role=="user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape=RoundedCornerShape(16.dp), modifier=Modifier.widthIn(max=340.dp)) {
                                    Text(if(line.text.isBlank() && busy) "در حال دریافت پاسخ..." else line.text, Modifier.padding(12.dp))
                                }
                                MessageActions(text = line.text, editable = line.role == "user") {
                                    prompt = line.text
                                    lines.subList(index, lines.size).clear()
                                    persistHistory()
                                    status = "پیام برای ویرایش به کادر نوشتن منتقل شد."
                                }
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal=10.dp, vertical=6.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("activity" to "فعالیت ایجنت", "terminal" to "ترمینال", "build" to "گزارش ساخت", "output" to "خروجی APK").forEach { (key,label) ->
                                    FilterChip(selected=activityPanel==key,onClick={activityPanel=key},label={Text(label)})
                                }
                            }
                            when(activityPanel) {
                                "activity" -> {
                                    Text(if(busy) "● ایجنت مشغول است" else "● آماده", color=if(busy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.labelMedium)
                                    agentEvents.takeLast(4).forEach { Text(it, style=MaterialTheme.typography.bodySmall, maxLines=1) }
                                }
                                "terminal" -> Text("ترمینال اجرایی هنوز فعال نیست. این بخش فعلاً وضعیت محیط را نشان می‌دهد و فرمانی اجرا نمی‌کند.", style=MaterialTheme.typography.bodySmall)
                                "build" -> {
                                    Text(buildStatus, style=MaterialTheme.typography.bodySmall)
                                    if(buildLog.isNotBlank()) Text(buildLog.takeLast(900), style=MaterialTheme.typography.bodySmall, maxLines=5)
                                }
                                "output" -> Text(buildOutput, style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                MainTab.PROJECTS -> ProjectHomeScreen(workspaceDirectory, { importLauncher.launch(null) }, { showWorkspace=true }, { showBuildManager=true }, { exportLauncher.launch("android-code-agent-project.zip") })
                MainTab.HISTORY -> HistoryHomeScreen(
                    conversations=conversations, activeId=activeConversationId,
                    onNew={
                        persistHistory()
                        val created=historyStore.create()
                        conversations=historyStore.loadAll()
                        activeConversationId=created.id
                        prompt=""
                        selectedTab=MainTab.CHAT
                    },
                    onOpen={ item ->
                        if(item.id!=activeConversationId) {
                            persistHistory()
                            activeConversationId=item.id
                            prompt=""
                            pendingImageData=null
                            pendingImageMime=null
                            pendingAttachmentName=null
                        }
                        selectedTab=MainTab.CHAT
                        status="گفتگوی " + item.title + " باز شد."
                    },
                    onRename={ item -> renameConversationId=item.id; renameConversationText=item.title },
                    onDelete={ item -> deletingConversationId=item.id }
                )
                MainTab.SETTINGS -> SettingsHomeScreen(
                    provider=config.provider, model=config.model, endpoint=config.baseUrl,
                    hasKey=keys.hasKey(config.provider), profilesCount=profiles.size,
                    onOpenSettings={
                        provider=config.provider
                        model=config.model
                        endpoint=config.baseUrl
                        apiKey=""
                        settings=true
                    },
                    onBuild={ showBuildManager=true }
                )
            }
            NavigationBar {
                NavigationBarItem(selected=selectedTab==MainTab.CHAT,onClick={selectedTab=MainTab.CHAT},icon={Icon(Icons.Default.Home,null)},label={Text("چت")})
                NavigationBarItem(selected=selectedTab==MainTab.PROJECTS,onClick={selectedTab=MainTab.PROJECTS},icon={Icon(Icons.Default.FolderOpen,null)},label={Text("پروژه‌ها")})
                NavigationBarItem(selected=selectedTab==MainTab.HISTORY,onClick={selectedTab=MainTab.HISTORY},icon={Icon(Icons.Default.History,null)},label={Text("تاریخچه")})
                NavigationBarItem(selected=selectedTab==MainTab.SETTINGS,onClick={selectedTab=MainTab.SETTINGS},icon={Icon(Icons.Default.Settings,null)},label={Text("تنظیمات")})
            }
        }

    }

    if (showConversationList) {
        AlertDialog(
            onDismissRequest = { showConversationList = false },
            title = { Text("گفتگوهای من") },
            text = {
                Column(Modifier.heightIn(max = 440.dp)) {
                    Button(onClick = {
                        persistHistory()
                        val created = historyStore.create()
                        conversations = historyStore.loadAll()
                        activeConversationId = created.id
                        prompt = ""
                        pendingImageData = null
                        pendingImageMime = null
                        pendingAttachmentName = null
                        showConversationList = false
                        status = "گفتگوی جدید ساخته شد."
                    }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ساخت گفتگوی جدید")
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(conversations, key = { it.id }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        if (item.id != activeConversationId) {
                                            persistHistory()
                                            activeConversationId = item.id
                                            prompt = ""
                                            pendingImageData = null
                                            pendingImageMime = null
                                            pendingAttachmentName = null
                                        }
                                        showConversationList = false
                                        status = "گفتگوی «${item.title}» باز شد."
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(item.title, maxLines = 1)
                                        Text("${item.messages.count { it.role == "user" }} پیام · ${if (item.id == activeConversationId) "فعال" else "ذخیره‌شده"}",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = {
                                    renameConversationId = item.id
                                    renameConversationText = item.title
                                }, enabled = !busy) { Icon(Icons.Default.Edit, "تغییر نام گفتگو") }
                                IconButton(onClick = { deletingConversationId = item.id }, enabled = !busy) {
                                    Icon(Icons.Default.Delete, "حذف گفتگو")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConversationList = false }) { Text("بستن") } }
        )
    }

    if (renameConversationId != null) {
        AlertDialog(
            onDismissRequest = { renameConversationId = null },
            title = { Text("تغییر نام گفتگو") },
            text = { OutlinedTextField(renameConversationText, { renameConversationText = it },
                label = { Text("نام گفتگو") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                historyStore.rename(renameConversationId!!, renameConversationText)
                conversations = historyStore.loadAll()
                renameConversationId = null
            }) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { renameConversationId = null }) { Text("انصراف") } }
        )
    }

    if (deletingConversationId != null) {
        AlertDialog(
            onDismissRequest = { deletingConversationId = null },
            title = { Text("حذف گفتگو؟") },
            text = { Text("پیام‌های این گفتگو برای همیشه از حافظه برنامه حذف می‌شوند.") },
            confirmButton = { TextButton(onClick = {
                val target = deletingConversationId!!
                historyStore.delete(target)
                var remaining = historyStore.loadAll()
                if (remaining.isEmpty()) {
                    historyStore.create()
                    remaining = historyStore.loadAll()
                }
                conversations = remaining
                if (target == activeConversationId) {
                    activeConversationId = remaining.first().id
                    prompt = ""
                }
                deletingConversationId = null
                showConversationList = false
                status = "گفتگو حذف شد."
            }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { deletingConversationId = null }) { Text("انصراف") } }
        )
    }

    if (showWorkspace) WorkspaceBrowser(workspaceDirectory) { showWorkspace = false }
    if (showHtmlPreview && !htmlPreview.isNullOrBlank()) HtmlPreviewDialog(htmlPreview, onDismiss = { showHtmlPreview = false })
    if (showBuildManager) BuildManagerDialog(keys = keys, onDismiss = { showBuildManager = false }, onBuildStatus = { buildStatus = it }, onBuildLog = { buildLog = it }, onBuildOutput = { buildOutput = it })

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("پاک کردن تاریخچه؟") },
            text = { Text("همه پیام‌های ذخیره‌شده این گفتگو از حافظه برنامه حذف می‌شوند.") },
            confirmButton = { TextButton(onClick = {
                lines.clear()
                lines.add(LiveLine("assistant", "تاریخچه پاک شد. درخواست برنامه‌نویسی جدیدت را بنویس."))
                historyStore.save(activeConversationId, activeConversationTitle, lines.map { ChatMessage(it.role, it.text) })
                conversations = historyStore.loadAll()
                confirmClearHistory = false
                status = "تاریخچه گفتگو پاک شد."
            }) { Text("پاک کن") } },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("انصراف") } }
        )
    }

    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("حذف کلید API؟") },
            text = { Text("کلید رمزنگاری‌شده سرویس «${config.provider}» از این دستگاه حذف می‌شود.") },
            confirmButton = { TextButton(onClick = {
                keys.delete(config.provider)
                confirmDeleteKey = false
                status = "کلید API این سرویس حذف شد."
            }) { Text("حذف کلید") } },
            dismissButton = { TextButton(onClick = { confirmDeleteKey = false }) { Text("انصراف") } }
        )
    }

    if (approvalPath != null) {
        AlertDialog(
            onDismissRequest = { approvalDecision?.complete(false) },
            title = { Text("اجازهٔ تغییر فایل؟") },
            text = { Text("مسیر: $approvalPath\\n\\nپیش‌نمایش محتوا (حداکثر ۸۰۰ نویسه):\\n$approvalPreview\\n\\nاگر مسیر و محتوا را بررسی نکرده‌ای، رد کن.") },
            confirmButton = { TextButton(onClick = { approvalDecision?.complete(true) }) { Text("تأیید نوشتن") } },
            dismissButton = { TextButton(onClick = { approvalDecision?.complete(false) }) { Text("رد") } }
        )
    }

    if(settings) {
        AlertDialog(
            onDismissRequest={settings=false},
            title={ Text("تنظیمات مدل") },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("مدیریت پروفایل‌های مدل و API", style=MaterialTheme.typography.titleSmall)
                    Box {
                        TextButton(onClick = { profilesExpanded = true }) { Text("انتخاب پروفایل ذخیره‌شده (${profiles.size})") }
                        DropdownMenu(expanded = profilesExpanded, onDismissRequest = { profilesExpanded = false }) {
                            profiles.forEach { saved ->
                                DropdownMenuItem(text = { Text("${saved.provider} · ${saved.model}") }, onClick = {
                                    provider = saved.provider
                                    model = saved.model
                                    endpoint = saved.baseUrl
                                    apiKey = ""
                                    profilesExpanded = false
                                })
                            }
                        }
                    }
                    TextButton(onClick = {
                        provider = "custom_${profiles.size + 1}"
                        model = ""
                        endpoint = config.baseUrl
                        apiKey = ""
                    }) { Text("افزودن پروفایل جدید") }
                    Text("شناسه هر پروفایل باید یکتا باشد؛ کلید API آن جداگانه ذخیره می‌شود.", style=MaterialTheme.typography.bodySmall)
                    Text("provider: openai / anthropic / compatible", style=MaterialTheme.typography.labelSmall)
                    OutlinedTextField(provider,{provider=it.trim().lowercase()},label={Text("Provider ID")},singleLine=true)
                    OutlinedTextField(model,{model=it},label={Text("Model")},singleLine=true)
                    OutlinedTextField(endpoint,{endpoint=it},label={Text("Base URL (HTTPS)")},singleLine=true)
                    OutlinedTextField(apiKey,{apiKey=it},label={Text("API Key")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
                    Text("کلید با Android Keystore رمزنگاری می‌شود. ابزارهای عامل از APIهای سازگار با OpenAI و Anthropic Messages پشتیبانی می‌کنند.", style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { confirmDeleteKey = true }, enabled = keys.hasKey(config.provider)) { Text("حذف کلید ذخیره‌شده این سرویس") }
                }
            },
            confirmButton={
                TextButton(onClick={
                    try {
                        val next=ProviderConfig(provider.trim(),model.trim(),endpoint.trim())
                        // Do not report success until the key is encrypted, persisted, and readable.
                        if(apiKey.isNotBlank()) {
                            keys.save(next.provider,apiKey.trim())
                        } else if(keys.read(next.provider).isNullOrBlank()) {
                            throw IllegalArgumentException("برای این سرویس کلید API وارد کن؛ کلید قبلی هم پیدا نشد.")
                        }
                        configs.save(next)
                        check(!keys.read(next.provider).isNullOrBlank()) {
                            "کلید API ذخیره شد اما هنگام بررسی دوباره خوانده نشد."
                        }
                        config=next
                        profiles=configs.loadProfiles()
                        apiKey=""
                        status="تنظیمات و کلید API با موفقیت ذخیره و بررسی شد."
                        settings=false
                    } catch(e:Exception) { status="خطا در ذخیره: ${e.message ?: "نامشخص"}" }
                }) { Text("ذخیره") }
            },
            dismissButton={ TextButton(onClick={settings=false}) { Text("بستن") } }
        )
    }
}
