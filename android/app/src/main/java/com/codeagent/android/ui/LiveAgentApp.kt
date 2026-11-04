package com.codeagent.android.ui

import com.codeagent.android.SecureKeyStore
import com.codeagent.android.llm.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private data class LiveLine(val role: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAgentApp(keys: SecureKeyStore, configs: ProviderConfigStore) {
    var config by remember { mutableStateOf(configs.load()) }
    var provider by remember { mutableStateOf(config.provider) }
    var model by remember { mutableStateOf(config.model) }
    var endpoint by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("API هنوز پیکربندی نشده است.") }
    val scope = rememberCoroutineScope()
    val client = remember { LlmClient() }
    val lines = remember { mutableStateListOf(LiveLine("assistant", "سلام. اتصال زندهٔ مدل آمادهٔ پیکربندی است.")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Android Code Agent"); Text("${config.provider} · ${config.model}", style = MaterialTheme.typography.labelSmall) } },
                actions = { IconButton(onClick = { provider=config.provider; model=config.model; endpoint=config.baseUrl; apiKey=""; settings=true }) { Icon(Icons.Default.Settings, "تنظیمات") } }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().imePadding().padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(prompt, { prompt=it }, Modifier.weight(1f), placeholder={ Text("درخواست برنامه‌نویسی...") }, maxLines=4)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick={
                    val question=prompt.trim()
                    if(question.isNotEmpty() && !busy) {
                        val secret=keys.read(config.provider)
                        if(secret.isNullOrBlank()) { status="کلید API ثبت نشده است."; settings=true }
                        else {
                            prompt=""
                            lines.add(LiveLine("user",question))
                            lines.add(LiveLine("assistant",""))
                            busy=true
                            status="در حال اتصال به ${config.provider}..."
                            val history=lines.dropLast(1).map { ChatMessage(it.role,it.text) }
                            scope.launch {
                                var response=""
                                client.stream(config,secret,history)
                                    .catch { e ->
                                        lines[lines.lastIndex]=LiveLine("assistant","خطای API: ${e.message ?: "نامشخص"}")
                                        status="درخواست ناموفق بود؛ کلید، مدل، endpoint و سهمیه را بررسی کن."
                                    }
                                    .collect { part ->
                                        response+=part
                                        lines[lines.lastIndex]=LiveLine("assistant",response)
                                    }
                                if(response.isNotEmpty()) status="پاسخ دریافت شد."
                                busy=false
                            }
                        }
                    }
                }, enabled=prompt.isNotBlank() && !busy) {
                    if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Icon(Icons.Default.Send,"ارسال")
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
                    Text("کلید با Android Keystore رمزنگاری می‌شود. برای سازگاری، endpoint باید API مربوط به /v1 باشد.", style=MaterialTheme.typography.bodySmall)
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
