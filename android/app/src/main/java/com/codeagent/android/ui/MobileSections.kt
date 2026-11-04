package com.codeagent.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codeagent.android.llm.SavedConversation
import java.io.File

@Composable
fun ProjectHomeScreen(workspaceDirectory: File, onImport: () -> Unit, onBrowse: () -> Unit, onBuild: () -> Unit, onExport: () -> Unit) {
    val files = workspaceDirectory.walkTopDown().filter { it.isFile }
        .filter { file -> file.toPath().none { it.toString() in setOf(".git", ".gradle", "build", ".idea") } }
        .take(100).toList()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("پروژه‌های من", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Workspace فعلی", style = MaterialTheme.typography.titleMedium)
                        Text(workspaceDirectory.name, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${files.size} فایل", style = MaterialTheme.typography.labelMedium)
                }
                Text("این نسخه یک فضای کاری محلی فعال دارد. برای تعویض پروژه از وارد کردن پروژه استفاده کن.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick=onImport, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp)); Text("وارد کردن پروژه") }
            OutlinedButton(onClick=onBrowse, modifier=Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen,null); Spacer(Modifier.width(6.dp)); Text("فایل‌ها") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onBuild, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Build,null); Text("ساخت APK") }
            OutlinedButton(onClick=onExport, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Archive,null); Text("خروجی ZIP") }
        }
        Text("فایل‌های اخیر", style=MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(files.takeLast(30).asReversed(), key={it.absolutePath}) { file ->
                ListItem(headlineContent={Text(file.relativeTo(workspaceDirectory).invariantSeparatorsPath, maxLines=1)}, supportingContent={Text("${file.length()} بایت")}, leadingContent={Icon(Icons.Default.FolderOpen,null)})
                HorizontalDivider()
            }
            if(files.isEmpty()) item { Text("پروژه‌ای وارد نشده است.", color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun HistoryHomeScreen(conversations: List<SavedConversation>, activeId: String, onNew: () -> Unit, onOpen: (SavedConversation) -> Unit, onRename: (SavedConversation) -> Unit, onDelete: (SavedConversation) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("تاریخچه گفتگوها", style=MaterialTheme.typography.headlineSmall, modifier=Modifier.weight(1f))
            Button(onClick=onNew) { Icon(Icons.Default.Add,null); Text("گفتگوی جدید") }
        }
        if(conversations.isEmpty()) Text("هنوز گفتگویی ذخیره نشده است.")
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(conversations, key={it.id}) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(item.title, style=MaterialTheme.typography.titleMedium, modifier=Modifier.weight(1f), maxLines=1)
                            if(item.id==activeId) AssistChip(onClick={},label={Text("فعال")})
                        }
                        Text("${item.messages.count { it.role == "user" }} پیام · ${item.messages.size} پیام در کل", style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={onOpen(item)}) { Text("باز کردن") }
                            TextButton(onClick={onRename(item)}) { Text("تغییر نام") }
                            TextButton(onClick={onDelete(item)}) { Text("حذف", color=MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsHomeScreen(provider: String, model: String, endpoint: String, hasKey: Boolean, profilesCount: Int, onOpenSettings: () -> Unit, onBuild: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات", style=MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("پیکربندی هوش مصنوعی", style=MaterialTheme.typography.titleMedium)
                Text("Provider: $provider")
                Text("Model: $model")
                Text("Endpoint: $endpoint", style=MaterialTheme.typography.bodySmall)
                Text("کلید API: ${if(hasKey) "ذخیره و رمزنگاری شده" else "تنظیم نشده"}")
                Text("پروفایل‌های ذخیره‌شده: $profilesCount")
                Button(onClick=onOpenSettings, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Settings,null); Spacer(Modifier.width(8.dp)); Text("مدیریت مدل و API") }
            }
        }
        OutlinedButton(onClick=onBuild, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Build,null); Spacer(Modifier.width(8.dp)); Text("تنظیمات و ساخت APK") }
        Text("کلیدهای API در حافظه خصوصی برنامه و با Android Keystore محافظت می‌شوند. کلید را در پیام‌ها یا فایل پروژه قرار نده.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
