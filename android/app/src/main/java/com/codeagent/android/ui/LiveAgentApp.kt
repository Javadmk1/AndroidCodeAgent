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

private data class LiveLine(val role: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAgentApp(keys: SecureKeyStore, configs: ProviderConfigStore) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(configs.load()) }
    var provider by remember { mutableStateOf(config.provider) }
    var model by remember { mutableStateOf(config.model) }
    var endpoint by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var showWorkspace by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var transferBusy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("API هنوز پیکربندی نشده است.") }
    val historyStore = remember(context) { ConversationStore(context) }
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
    val lines = remember(historyStore) {
        mutableStateListOf<LiveLine>().apply {
            addAll(historyStore.load().map { LiveLine(it.role, it.content) })
            if (isEmpty()) add(LiveLine("assistant", "سلام. درخواستت را بنویس؛ ابزارهای پروژه در فضای داخلی برنامه اجرا می‌شوند."))
        }
    }
    fun persistHistory() {
        historyStore.save(lines.map { ChatMessage(it.role, it.text) })
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
                title = { Column { Text("Android Code Agent"); Text("${config.provider} · ${config.model}", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    IconButton(onClick = { importLauncher.launch(null) }, enabled = !transferBusy) { Icon(Icons.Default.FolderOpen, "وارد کردن پوشه پروژه") }
                    IconButton(onClick = { showWorkspace = true }) { Icon(Icons.Default.Folder, "مرور و ویرایش فایل‌های پروژه") }
                    IconButton(onClick = { confirmClearHistory = true }, enabled = !busy) { Icon(Icons.Default.Delete, "پاک کردن تاریخچه گفتگو") }
                    IconButton(onClick = { exportLauncher.launch("android-code-agent-project.zip") }, enabled = !transferBusy) { Icon(Icons.Default.Archive, "خروجی ZIP پروژه") }
                    IconButton(onClick = { provider=config.provider; model=config.model; endpoint=config.baseUrl; apiKey=""; settings=true }) { Icon(Icons.Default.Settings, "تنظیمات") }
                }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().imePadding().padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(prompt, { prompt=it }, Modifier.weight(1f), placeholder={ Text("درخواست برنامه‌نویسی...") }, maxLines=4)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick={
                    if (busy) {
                        activeJob?.cancel()
                        status = "درخواست لغو شد."
                    } else {
                    val question=prompt.trim()
                    if(question.isNotEmpty()) {
                        val secret=keys.read(config.provider)
                        if(secret.isNullOrBlank()) { status="کلید API ثبت نشده است."; settings=true }
                        else {
                            prompt=""
                            lines.add(LiveLine("user",question))
                            lines.add(LiveLine("assistant",""))
                            persistHistory()
                            busy=true
                            status="در حال پردازش درخواست..."
                            val history=lines.dropLast(1).map { ChatMessage(it.role,it.text) }
                            activeJob = scope.launch {
                                try {
                                    val result = runtime.run(
                                        config = config,
                                        apiKey = secret,
                                        conversation = history.map { it.role to it.content },
                                        onText = { chunk ->
                                            withContext(Dispatchers.Main.immediate) {
                                                if (lines.isNotEmpty()) {
                                                    val current = lines.last()
                                                    lines[lines.lastIndex] = LiveLine("assistant", current.text + chunk)
                                                }
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
                                    val toolSummary = if (result.toolLog.isEmpty()) "" else
                                        "\n\nگزارش ابزارها:\n" + result.toolLog.joinToString("\n")
                                    lines[lines.lastIndex]=LiveLine("assistant",result.answer + toolSummary)
                                    persistHistory()
                                    status="درخواست به پایان رسید."
                                } catch(e: CancellationException) {
                                    persistHistory()
                                    status = "درخواست لغو شد."
                                } catch(e:Exception) {
                                    lines[lines.lastIndex]=LiveLine("assistant","خطا: ${e.message ?: "نامشخص"}")
                                    persistHistory()
                                    status="درخواست ناموفق بود؛ مدل، کلید، endpoint و پشتیبانی ابزارها را بررسی کن."
                                } finally { busy=false; activeJob=null }
                            }
                        }
                    }
                    }
                }, enabled=(prompt.isNotBlank() && !busy) || busy) {
                    if(busy) Icon(Icons.Default.Close,"لغو درخواست") else Icon(Icons.Default.Send,"ارسال")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(status, Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=8.dp), style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(lines) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=if(line.role=="user") Arrangement.End else Arrangement.Start) {
                        Surface(color=if(line.role=="user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape=RoundedCornerShape(16.dp), modifier=Modifier.widthIn(max=340.dp)) {
                            Text(if(line.text.isBlank() && busy) "در حال دریافت پاسخ..." else line.text, Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (showWorkspace) WorkspaceBrowser(workspaceDirectory) { showWorkspace = false }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("پاک کردن تاریخچه؟") },
            text = { Text("همه پیام‌های ذخیره‌شده این گفتگو از حافظه برنامه حذف می‌شوند.") },
            confirmButton = { TextButton(onClick = {
                lines.clear()
                lines.add(LiveLine("assistant", "تاریخچه پاک شد. درخواست برنامه‌نویسی جدیدت را بنویس."))
                historyStore.clear()
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
                        configs.save(next)
                        if(apiKey.isNotBlank()) keys.save(next.provider,apiKey.trim())
                        config=next
                        apiKey=""
                        status=if(keys.hasKey(next.provider)) "تنظیمات و کلید ذخیره شد." else "تنظیمات ذخیره شد؛ کلید API وارد نشده."
                        settings=false
                    } catch(e:Exception) { status="خطا در ذخیره: ${e.message ?: "نامشخص"}" }
                }) { Text("ذخیره") }
            },
            dismissButton={ TextButton(onClick={settings=false}) { Text("بستن") } }
        )
    }
}
