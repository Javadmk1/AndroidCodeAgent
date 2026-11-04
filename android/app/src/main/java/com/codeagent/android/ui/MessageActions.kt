package com.codeagent.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MessageActions(text: String, editable: Boolean, onEdit: () -> Unit) {
    val context = LocalContext.current
    Row {
        IconButton(onClick = {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy message") }
        if (editable) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit message") }
    }
}
