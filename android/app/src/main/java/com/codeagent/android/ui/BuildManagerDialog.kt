package com.codeagent.android.ui

import android.content.Intent
import android.net.Uri
import com.codeagent.android.SecureKeyStore
import com.codeagent.android.agent.CloudBuildClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@Composable
fun BuildManagerDialog(
    keys: SecureKeyStore,
    onDismiss: () -> Unit,
    onBuildStatus: (String) -> Unit = {},
    onBuildLog: (String) -> Unit = {},
    onBuildOutput: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(context) { CloudBuildClient(context) }
    val prefs = remember(context) { context.getSharedPreferences("cloud_builder_config", 0) }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var projectFolder by remember { mutableStateOf(".") }
    var task by remember { mutableStateOf("assembleDebug") }
    var cloudEndpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var cloudToken by remember { mutableStateOf(keys.read("cloud_builder") ?: "") }
    var cloudProjectDir by remember { mutableStateOf(".") }
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var buildId by remember { mutableStateOf<String?>(null) }
    var artifactFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("سرویس ابری هنوز پیکربندی نشده است.") }
    var buildLog by remember { mutableStateOf("") }
    var artifactSummary by remember { mutableStateOf("") }

    LaunchedEffect(status) { onBuildStatus(status) }
    LaunchedEffect(buildLog) { onBuildLog(buildLog) }
    LaunchedEffect(artifactFile) {
        artifactFile?.let { file ->
            artifactSummary = runCatching {
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }
                        .map { "${it.name} · ${it.size} بایت" }.toList()
                    if (entries.isEmpty()) "در ZIP فایل APK پیدا نشد." else entries.joinToString("\n")
                }
            }.getOrElse { "فهرست APKها قابل خواندن نیست: ${it.message ?: "خطای ZIP"}" }
            onBuildOutput("خروجی: ${file.name} · ${file.length()} بایت\n" + artifactSummary)
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            zipUri = uri
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            status = "فایل ZIP انتخاب شد."
        }
    }
    val saveArtifact = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = artifactFile
        if (uri != null && source != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("فایل خروجی قابل ذخیره نیست.")
                }
                status = "فایل APKها ذخیره شد."
            } catch (e: Exception) {
                status = "خطا در ذخیره خروجی: ${e.message ?: "نامشخص"}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("مدیریت ساخت APK") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ساخت از GitHub Actions", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(repo, { repo = it }, label = { Text("مخزن عمومی owner/name") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("شاخه یا commit") }, singleLine = true)
                OutlinedTextField(projectFolder, { projectFolder = it }, label = { Text("پوشه پروژه") }, singleLine = true)
                OutlinedTextField(task, { task = it }, label = { Text("Gradle task") }, singleLine = true)
                Button(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val values = "repository=$repo\nref=$branch\nproject_dir=$projectFolder\ngradle_task=$task"
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Build inputs", values))
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Javadmk1/AndroidCodeAgent/actions/workflows/build-imported-project.yml")))
                }, enabled = repo.isNotBlank() && branch.isNotBlank() && task.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("کپی تنظیمات و باز کردن GitHub Actions")
                }
                HorizontalDivider()
                Text("ساخت ابری با آپلود ZIP", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(cloudEndpoint, { cloudEndpoint = it }, label = { Text("آدرس HTTPS سرویس ساخت") }, singleLine = true)
                OutlinedTextField(cloudToken, { cloudToken = it }, label = { Text("توکن سرویس ساخت") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(cloudProjectDir, { cloudProjectDir = it }, label = { Text("پوشه پروژه داخل ZIP") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }, enabled = !busy) {
                        Text(if (zipUri == null) "انتخاب ZIP" else "تغییر ZIP")
                    }
                    OutlinedButton(onClick = {
                        try {
                            client.validateEndpoint(cloudEndpoint)
                            require(cloudToken.isNotBlank()) { "توکن را وارد کن." }
                            prefs.edit().putString("endpoint", cloudEndpoint.trim().trimEnd('/')).apply()
                            keys.save("cloud_builder", cloudToken.trim())
                            status = "تنظیمات سرویس ابری ذخیره شد."
                        } catch (e: Exception) { status = "خطا: ${e.message ?: "نامشخص"}" }
                    }, enabled = !busy) { Text("ذخیره اتصال") }
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            buildLog = ""
                            artifactFile = null
                            try {
                                val token = keys.read("cloud_builder") ?: cloudToken.trim()
                                val endpoint = prefs.getString("endpoint", null) ?: cloudEndpoint.trim().trimEnd('/')
                                val selected = zipUri ?: error("ابتدا فایل ZIP پروژه را انتخاب کن.")
                                require(token.isNotBlank()) { "توکن سرویس ساخت ذخیره نشده است." }
                                status = "در حال بارگذاری پروژه..."
                                val id = client.submitZip(endpoint, token, selected, cloudProjectDir.trim())
                                buildId = id
                                status = "ساخت در صف قرار گرفت."
                                var attempts = 0
                                while (attempts < 700) {
                                    val result = client.getStatus(endpoint, token, id)
                                    buildLog = result.log
                                    status = when (result.status) {
                                        "queued" -> "در صف ساخت..."
                                        "running" -> "در حال ساخت APK..."
                                        "completed" -> "ساخت با موفقیت تمام شد."
                                        "failed" -> "ساخت ناموفق بود: ${result.error ?: "خطای Gradle"}"
                                        else -> "وضعیت: ${result.status}"
                                    }
                                    if (result.status == "completed" && result.artifactReady) {
                                        artifactFile = client.downloadArtifact(endpoint, token, id)
                                        status = "APK آماده دریافت است."
                                        break
                                    }
                                    if (result.status == "failed") break
                                    delay(3000)
                                    attempts++
                                }
                                if (attempts >= 700) status = "زمان انتظار برنامه تمام شد؛ شناسه ساخت را نگه دار و وضعیت سرور را بررسی کن."
                            } catch (e: Exception) {
                                status = "خطای ساخت ابری: ${e.message ?: "نامشخص"}"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy && zipUri != null && cloudEndpoint.isNotBlank() && cloudToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "در حال ساخت..." else "شروع ساخت ابری") }
                if (artifactFile != null) {
                    Text("فایل‌های APK داخل خروجی:", style = MaterialTheme.typography.titleSmall)
                    Text(artifactSummary, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { saveArtifact.launch("android-code-agent-apks.zip") }, modifier = Modifier.fillMaxWidth()) {
                        Text("ذخیره ZIP شامل APKها")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (buildLog.isNotBlank()) Text(buildLog.takeLast(3500), style = MaterialTheme.typography.bodySmall)
                Text("سرویس ابری باید جداگانه روی سرور Linux با Docker مستقر شود. راهنما:", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Javadmk1/AndroidCodeAgent/blob/main/cloud-builder/README.md")))
                }) { Text("راهنمای راه‌اندازی سرور ابری") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("بستن") } }
    )
}
