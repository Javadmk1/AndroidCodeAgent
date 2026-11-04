package com.codeagent.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun BuildManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var projectFolder by remember { mutableStateOf(".") }
    var task by remember { mutableStateOf("assembleDebug") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت ساخت APK") },
        text = {
            Column {
                Text("ساخت از GitHub Actions")
                OutlinedTextField(repo, { repo = it }, label = { Text("مخزن عمومی owner/name") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("شاخه یا commit") }, singleLine = true)
                OutlinedTextField(projectFolder, { projectFolder = it }, label = { Text("پوشه پروژه") }, singleLine = true)
                OutlinedTextField(task, { task = it }, label = { Text("Gradle task") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val values = "repository=$repo\nref=$branch\nproject_dir=$projectFolder\ngradle_task=$task"
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Build inputs", values))
                    android.widget.Toast.makeText(context, "تنظیمات ساخت کپی شد", android.widget.Toast.LENGTH_SHORT).show()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Javadmk1/AndroidCodeAgent/actions/workflows/build-imported-project.yml")))
                }, enabled = repo.isNotBlank() && branch.isNotBlank() && task.isNotBlank()) {
                    Text("کپی تنظیمات و باز کردن GitHub Actions")
                }
                Spacer(Modifier.height(12.dp))
                Text("ساخت مستقیم ZIP در فضای ابری")
                Text("سرویس ساخت ابری به‌صورت کد در مخزن اضافه شده، اما هنوز روی سرور شما مستقر و به برنامه متصل نشده است.")
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Javadmk1/AndroidCodeAgent/blob/main/cloud-builder/README.md")))
                }) { Text("راهنمای راه‌اندازی سرور ابری") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}
