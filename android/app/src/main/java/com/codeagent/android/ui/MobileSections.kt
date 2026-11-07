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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codeagent.android.llm.SavedConversation
import com.codeagent.android.agent.ProjectRecord
import java.io.File

@Composable
fun ProjectHomeScreen(
    workspaceDirectory: File,
    projects: List<ProjectRecord>,
    activeProjectId: String,
    onSelectProject: (ProjectRecord) -> Unit,
    onCreateProject: (String) -> Unit,
    onRenameProject: (ProjectRecord, String) -> Unit,
    onDeleteProject: (ProjectRecord) -> Unit,
    onImport: () -> Unit,
    onImportZip: () -> Unit,
    onBrowse: () -> Unit,
    onBuild: () -> Unit,
    onExport: () -> Unit
) {
    val files = workspaceDirectory.walkTopDown().filter { it.isFile }
        .filter { file -> file.toPath().none { it.toString() in setOf(".git", ".gradle", "build", ".idea") } }
        .take(100).toList()
    var createDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ProjectRecord?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectRecord?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("پروژه‌های من", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { createName = ""; createDialog = true }) { Icon(Icons.Default.Add, null); Text("پروژه جدید") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("پروژه فعال", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(projects.firstOrNull { it.id == activeProjectId }?.name ?: workspaceDirectory.name, style = MaterialTheme.typography.titleLarge)
                Text("${files.size} فایل در فضای کاری فعلی", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("همه پروژه‌ها", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { project ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = if (project.id == activeProjectId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (project.id == activeProjectId) "پروژه فعال" else "فضای کاری مستقل", style = MaterialTheme.typography.labelSmall)
                            }
                            if (project.id != activeProjectId) TextButton(onClick = { onSelectProject(project) }) { Text("باز کردن") }
                        }
                        if (project.id == activeProjectId) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("پوشه") }
                                OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) { Text("فایل ZIP") }
                                OutlinedButton(onClick = onBrowse, modifier = Modifier.weight(1f)) { Text("فایل‌ها") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onBuild, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Build,null); Text("ساخت APK") }
                                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Archive,null); Text("خروجی ZIP") }
                            }
                        }
                        Row {
                            TextButton(onClick = { renameTarget = project; renameName = project.name }) { Text("تغییر نام") }
                            if (projects.size > 1) TextButton(onClick = { deleteTarget = project }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        Text("فایل‌های اخیر پروژه فعال", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(files.takeLast(20).asReversed(), key={it.absolutePath}) { file ->
                ListItem(headlineContent={Text(file.relativeTo(workspaceDirectory).invariantSeparatorsPath, maxLines=1)}, supportingContent={Text("${file.length()} بایت")}, leadingContent={Icon(Icons.Default.FolderOpen,null)})
            }
            if(files.isEmpty()) item { Text("این پروژه هنوز فایلی ندارد.", color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("ساخت پروژه جدید") },
        text = { OutlinedTextField(createName, { createName = it }, label = { Text("نام پروژه") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreateProject(createName); createDialog = false }, enabled = createName.isNotBlank()) { Text("ساخت") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("انصراف") } }
    )
    renameTarget?.let { project -> AlertDialog(
        onDismissRequest = { renameTarget = null },
        title = { Text("تغییر نام پروژه") },
        text = { OutlinedTextField(renameName, { renameName = it }, label = { Text("نام جدید") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRenameProject(project, renameName); renameTarget = null }, enabled = renameName.isNotBlank()) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("انصراف") } }
    ) }
    deleteTarget?.let { project -> AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("حذف پروژه؟") },
        text = { Text("تمام فایل‌های پروژه «${project.name}» حذف می‌شوند. این کار قابل بازگشت نیست.") },
        confirmButton = { TextButton(onClick = { onDeleteProject(project); deleteTarget = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("انصراف") } }
    ) }
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
