package com.codeagent.android.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlPreviewDialog(html: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HTML Preview") },
        text = {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(460.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                },
                update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
