package com.codeagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codeagent.android.agent.WorkspaceTools
import java.io.File

@Composable
fun WorkspaceBrowser(workspaceDirectory: File, onDismiss: () -> Unit) {
    val tools = remember(workspaceDirectory) { WorkspaceTools(workspaceDirectory) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf(false) }
    var newFileDialog by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val files = remember(refresh) {
        workspaceDirectory.walkTopDown()
            .filter { it.isFile && it.length() <= 1_048_576L }
            .take(500)
            .map { workspaceDirectory.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
            .filter { path -> path.split('/').none { it.startsWith(".") || it in setOf(".git", ".gradle", "build", ".idea") } }
            .sorted()
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedPath == null) "فایل‌های پروژه" else selectedPath!!) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedPath == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("حداکثر ۵۰۰ فایل اول؛ فایل‌های متنی تا ۱ مگابایت قابل ویرایش‌اند.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { newFileDialog = true }) { Text("فایل جدید") }
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        if (files.isEmpty()) item { Text("هنوز فایلی در پروژه نیست.") }
                        items(files) { path ->
                            Text(
                                path,
                                Modifier.fillMaxWidth().clickable {
                                    try {
                                        editorText = tools.readFile(path)
                                        selectedPath = path
                                        error = null
                                    } catch (e: Exception) {
                                        error = e.message ?: "خواندن فایل ناموفق بود."
                                    }
                                }.padding(vertical = 9.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                        label = { Text("محتوای فایل") },
                        maxLines = 24
                    )
                    Text("ذخیره فقط پس از تأیید نهایی انجام می‌شود.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (selectedPath != null) {
                TextButton(onClick = { pendingSave = true }) { Text("ذخیره تغییرات") }
            } else {
                TextButton(onClick = onDismiss) { Text("بستن") }
            }
        },
        dismissButton = {
            if (selectedPath != null) TextButton(onClick = { selectedPath = null; error = null }) { Text("بازگشت") }
        }
    )

    if (pendingSave && selectedPath != null) {
        AlertDialog(
            onDismissRequest = { pendingSave = false },
            title = { Text("تأیید ذخیره فایل") },
            text = { Text("محتوای فایل «$selectedPath» جایگزین شود؟ این تغییر قابل بازگشت خودکار نیست.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        tools.writeFile(selectedPath!!, editorText)
                        pendingSave = false
                        selectedPath = null
                        refresh++
                        error = null
                    } catch (e: Exception) {
                        pendingSave = false
                        error = e.message ?: "ذخیره فایل ناموفق بود."
                    }
                }) { Text("تأیید و ذخیره") }
            },
            dismissButton = { TextButton(onClick = { pendingSave = false }) { Text("انصراف") } }
        )
    }

    if (newFileDialog) {
        AlertDialog(
            onDismissRequest = { newFileDialog = false },
            title = { Text("ساخت فایل") },
            text = {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("مسیر نسبی، مثل app/src/Main.kt") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        require(newPath.isNotBlank()) { "مسیر فایل را وارد کن." }
                        require(!File(newPath).isAbsolute) { "مسیر باید نسبی باشد." }
                        require(!File(workspaceDirectory, newPath).exists()) { "این فایل از قبل وجود دارد." }
                        tools.writeFile(newPath, "")
                        newPath = ""
                        newFileDialog = false
                        refresh++
                    } catch (e: Exception) {
                        error = e.message ?: "ساخت فایل ناموفق بود."
                        newFileDialog = false
                    }
                }) { Text("ساخت") }
            },
            dismissButton = { TextButton(onClick = { newFileDialog = false }) { Text("انصراف") } }
        )
    }
}
