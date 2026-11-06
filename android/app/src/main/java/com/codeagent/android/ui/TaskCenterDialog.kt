package com.codeagent.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codeagent.android.agent.WorkItem
import com.codeagent.android.agent.WorkKind

@Composable
fun TaskCenterDialog(
    items: List<WorkItem>,
    onAddPrompt: (String) -> Unit,
    onAddPlanStep: (String, String) -> Unit,
    onToggle: (WorkItem, Boolean) -> Unit,
    onRemove: (WorkItem) -> Unit,
    onUsePrompt: (WorkItem) -> Unit,
    onDismiss: () -> Unit
) {
    var promptText by remember { mutableStateOf("") }
    var planTitle by remember { mutableStateOf("") }
    var planDetails by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(WorkKind.QUEUED_PROMPT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صف کارها و برنامه پروژه") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == WorkKind.QUEUED_PROMPT, onClick = { mode = WorkKind.QUEUED_PROMPT }, label = { Text("صف درخواست‌ها") })
                    FilterChip(selected = mode == WorkKind.PLAN_STEP, onClick = { mode = WorkKind.PLAN_STEP }, label = { Text("مراحل برنامه") })
                }
                if (mode == WorkKind.QUEUED_PROMPT) {
                    OutlinedTextField(promptText, { promptText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("درخواست بعدی") }, minLines = 2)
                    Button(onClick = { onAddPrompt(promptText); promptText = "" }, enabled = promptText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("افزودن به صف") }
                } else {
                    OutlinedTextField(planTitle, { planTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("عنوان مرحله") }, singleLine = true)
                    OutlinedTextField(planDetails, { planDetails = it }, modifier = Modifier.fillMaxWidth(), label = { Text("شرح و معیار تکمیل") }, minLines = 2)
                    Button(onClick = { onAddPlanStep(planTitle, planDetails); planTitle = ""; planDetails = "" }, enabled = planTitle.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("افزودن مرحله") }
                }
                HorizontalDivider()
                Text("درخواست‌ها: " + items.count { it.kind == WorkKind.QUEUED_PROMPT && !it.completed } + " · مراحل: " + items.count { it.kind == WorkKind.PLAN_STEP && !it.completed }, style = MaterialTheme.typography.labelMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.id }) { item ->
                        Card {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (item.kind == WorkKind.PLAN_STEP) {
                                    Checkbox(item.completed, { onToggle(item, it) })
                                } else {
                                    IconButton(onClick = { onUsePrompt(item) }) { Text("↗") }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            item.kind == WorkKind.PLAN_STEP && item.completed -> "مرحله تکمیل شده"
                                            item.kind == WorkKind.PLAN_STEP -> "مرحله برنامه"
                                            else -> "در صف اجرا · برای انتقال به چت لمس کن"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(onClick = { onRemove(item) }) { Text("×") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}
