package com.codeagent.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun BuildManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت ساخت APK") },
        text = { Text("ساخت از GitHub Actions در دسترس است. برای ساخت مستقیم از ZIP باید سرویس ابری ایزوله جداگانه مستقر شود.") },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Javadmk1/AndroidCodeAgent/actions/workflows/build-imported-project.yml")))
            }) { Text("باز کردن GitHub Actions") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}
