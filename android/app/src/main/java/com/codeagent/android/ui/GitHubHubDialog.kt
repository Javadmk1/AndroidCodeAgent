package com.codeagent.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codeagent.android.SecureKeyStore
import com.codeagent.android.github.GitHubApiClient
import com.codeagent.android.github.GitHubPullRequest
import kotlinx.coroutines.launch

@Composable
fun GitHubHubDialog(keys: SecureKeyStore, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { GitHubApiClient() }
    var repository by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var head by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var pullRequests by remember { mutableStateOf<List<GitHubPullRequest>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var repoInfo by remember { mutableStateOf("") }
    var confirmCreate by remember { mutableStateOf(false) }
    fun savedToken(): String? = token.trim().takeIf { it.isNotBlank() } ?: keys.read("github")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اتصال GitHub") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repository, { repository = it }, modifier = Modifier.fillMaxWidth(), label = { Text("مخزن · owner/repository") }, singleLine = true)
                OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("GitHub token (اختیاری برای مخزن عمومی)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching { keys.save("github", token.trim()) }
                            .onSuccess { token = ""; error = "توکن به‌صورت رمزنگاری‌شده ذخیره شد." }
                            .onFailure { error = it.message ?: "ذخیره توکن ناموفق بود." }
                    }, enabled = token.isNotBlank()) { Text("ذخیره امن توکن") }
                    Button(onClick = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                val secret = savedToken()
                                val info = api.repository(repository, secret)
                                repoInfo = info.fullName + " · شاخه پیش‌فرض: " + info.defaultBranch + if (info.private) " · خصوصی" else " · عمومی"
                                branches = api.branches(repository, secret).map { it.name }
                                pullRequests = api.pullRequests(repository, secret)
                                if (base.isBlank()) base = info.defaultBranch
                                onStatus("اطلاعات GitHub بارگذاری شد.")
                            } catch (e: Exception) { error = e.message ?: "بارگذاری مخزن ناموفق بود." }
                            finally { busy = false }
                        }
                    }, enabled = repository.isNotBlank() && !busy) { Text(if (busy) "در حال بارگذاری..." else "بارگذاری مخزن") }
                }
                if (repoInfo.isNotBlank()) Text(repoInfo, style = MaterialTheme.typography.bodySmall)
                if (branches.isNotEmpty()) {
                    Text("شاخه‌ها", style = MaterialTheme.typography.titleSmall)
                    branches.take(30).forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                }
                Text("Pull Requestهای باز: " + pullRequests.size, style = MaterialTheme.typography.titleSmall)
                pullRequests.forEach { pr ->
                    TextButton(onClick = { onStatus("PR #" + pr.number + ": " + pr.url) }) {
                        Text("#" + pr.number + " · " + pr.title + " (" + pr.head + " → " + pr.base + ")")
                    }
                }
                HorizontalDivider()
                Text("ایجاد Pull Request", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(head, { head = it }, modifier = Modifier.fillMaxWidth(), label = { Text("شاخه مبدأ (head)") }, singleLine = true)
                OutlinedTextField(base, { base = it }, modifier = Modifier.fillMaxWidth(), label = { Text("شاخه مقصد (base)") }, singleLine = true)
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("عنوان PR") }, singleLine = true)
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth(), label = { Text("توضیحات") }, minLines = 2)
                Text("ایجاد PR فقط درخواست ادغام می‌سازد؛ هیچ کدی را خودکار merge نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { confirmCreate = true }, enabled = !busy && repository.isNotBlank() && head.isNotBlank() && base.isNotBlank() && title.isNotBlank() && !savedToken().isNullOrBlank()) { Text("ایجاد PR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
    if (confirmCreate) AlertDialog(
        onDismissRequest = { confirmCreate = false },
        title = { Text("تأیید ایجاد Pull Request") },
        text = { Text("در مخزن " + repository + " یک PR از شاخه " + head + " به " + base + " با عنوان «" + title + "» ایجاد شود؟") },
        confirmButton = { TextButton(onClick = {
            confirmCreate = false
            scope.launch {
                busy = true; error = ""
                try {
                    val result = api.createPullRequest(repository, head, base, title, body, savedToken().orEmpty())
                    onStatus("Pull Request #" + result.number + " ساخته شد: " + result.url)
                    onDismiss()
                } catch (e: Exception) { error = e.message ?: "ساخت PR ناموفق بود." }
                finally { busy = false }
            }
        }) { Text("تأیید و ایجاد") } },
        dismissButton = { TextButton(onClick = { confirmCreate = false }) { Text("انصراف") } }
    )
}
