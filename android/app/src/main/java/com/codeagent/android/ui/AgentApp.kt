package com.codeagent.android.ui

import com.codeagent.android.SecureKeyStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private data class ChatLine(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApp(secureKeyStore: SecureKeyStore) {
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var draftKey by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("openai") }
    var status by remember { mutableStateOf("کلید API هنوز تنظیم نشده است.") }
    val messages = remember {
        mutableStateListOf(
            ChatLine("سلام! من Android Code Agent هستم. در این نسخه، زیرساخت اولیه و تنظیم امن کلید API آماده شده است.", false)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Android Code Agent", style = MaterialTheme.typography.titleMedium)
                        Text("مرحلهٔ ۱ · زیرساخت اولیه", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("درخواست برنامه‌نویسی...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                messages.add(ChatLine(text, true))
                                messages.add(ChatLine("رابط گفتگو آماده است؛ اتصال مدل و اجرای درخواست‌ها در مرحلهٔ ۲ پیاده‌سازی می‌شود.", false))
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "ارسال")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("وضعیت پروژه", style = MaterialTheme.typography.titleSmall)
                        Text("رابط کاربری فعال است. برای گفتگو با مدل، ابتدا کلید API را در تنظیمات ثبت کن.")
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(messages) { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("تنظیمات اتصال مدل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ارائه‌دهنده")
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = when (provider) {
                                "openai" -> "OpenAI"
                                "anthropic" -> "Anthropic"
                                "compatible" -> "OpenAI-compatible"
                                else -> provider
                            },
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "compatible" to "OpenAI-compatible").forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { provider = id; expanded = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        placeholder = { Text("کلید را وارد کن") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    Text("کلید پس از ذخیره با AES-GCM رمزنگاری می‌شود و کلید رمزنگاری در Android Keystore می‌ماند.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (draftKey.isNotBlank()) {
                            secureKeyStore.save(provider, draftKey.trim())
                            draftKey = ""
                            status = "کلید $provider به‌شکل رمزنگاری‌شده ذخیره شد."
                        } else if (secureKeyStore.hasKey(provider)) {
                            status = "برای $provider کلید ذخیره‌شده وجود دارد."
                        } else {
                            status = "هنوز کلیدی برای $provider ذخیره نشده است."
                        }
                        showSettings = false
                    } catch (e: Exception) {
                        status = "ذخیره کلید ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                    }
                }) { Text("ذخیره") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("بستن") }
            }
        )
    }
}
