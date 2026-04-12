package demo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import demo.android.db.Priority
import demo.android.db.Task
import demo.android.db.User

/**
 * Entry composable for the demo app. Displays a list of tasks and lets the
 * user toggle completion, delete a task, or add a new one — every action goes
 * through [TasksViewModel] and ends up in the SQLite database via Stormify.
 *
 * The screen is intentionally tiny: ~150 lines of Compose so the demo's
 * "Stormify story" stays in focus instead of getting buried under UI plumbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(viewModel: TasksViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Stormify Tasks") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        }
    ) { padding ->
        TaskList(
            tasks = state.tasks,
            onToggle = viewModel::toggleCompleted,
            onDelete = viewModel::deleteTask,
            contentPadding = padding,
        )
    }

    if (showAddDialog) {
        AddTaskDialog(
            users = state.users,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, desc, priority, owner ->
                viewModel.addTask(title, desc, priority, owner)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text("No tasks yet. Tap + to add one.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tasks, key = { it.id ?: 0 }) { task ->
            TaskRow(task = task, onToggle = { onToggle(task) }, onDelete = { onDelete(task) })
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                )
                if (task.description.isNotBlank()) {
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityChip(task.priority)
                    Text(
                        text = "  ${task.user?.name ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun PriorityChip(priority: Priority) {
    val color = when (priority) {
        Priority.LOW -> Color(0xFF4CAF50)
        Priority.MEDIUM -> Color(0xFFFFA000)
        Priority.HIGH -> Color(0xFFE53935)
    }
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(priority.name, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    users: List<User>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String, priority: Priority, owner: User) -> Unit,
) {
    if (users.isEmpty()) {
        // Defensive: the seed step always inserts users, but show a hint just in case.
        Dialog(onDismissRequest = onDismiss) {
            Card(modifier = Modifier.padding(16.dp)) {
                Text(
                    "No users available — seed the database first.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var owner by remember { mutableStateOf(users.first()) }
    var priorityExpanded by remember { mutableStateOf(false) }
    var ownerExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.padding(16.dp), shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New Task", style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = priorityExpanded,
                    onExpandedChange = { priorityExpanded = !priorityExpanded },
                ) {
                    OutlinedTextField(
                        value = priority.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Priority") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = priorityExpanded,
                        onDismissRequest = { priorityExpanded = false },
                    ) {
                        Priority.values().forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { priority = p; priorityExpanded = false },
                            )
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = ownerExpanded,
                    onExpandedChange = { ownerExpanded = !ownerExpanded },
                ) {
                    OutlinedTextField(
                        value = owner.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Owner") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ownerExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = ownerExpanded,
                        onDismissRequest = { ownerExpanded = false },
                    ) {
                        users.forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u.name ?: "(no name)") },
                                onClick = { owner = u; ownerExpanded = false },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = { onConfirm(title, description, priority, owner) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Create") }
                }
            }
        }
    }
}
