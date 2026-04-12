package demo.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.android.db.Database
import demo.android.db.Priority
import demo.android.db.Task
import demo.android.db.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import onl.ycode.stormify.Stormify

/**
 * Single-screen ViewModel that hands the UI a list of [Task]s plus the
 * available [User]s for the "create task" picker.
 *
 * All Stormify calls happen on [Dispatchers.IO] — Android forbids disk I/O on
 * the main thread, and Stormify is a blocking, JDBC-shaped API. The ViewModel
 * holds the [Stormify] reference, the UI never touches it directly.
 *
 * State is exposed as a single immutable [TasksUiState] [StateFlow] so the
 * Composables can render off a single source of truth and never have to worry
 * about partial loads.
 */
class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val stormify: Stormify = Database.open(app)

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val (tasks, users) = withContext(Dispatchers.IO) {
            // findAll loads every Task; AutoTable lazy-loads each task.user the
            // first time it's read. Touching `task.user` here forces the load
            // before we hand the data to Compose, so the UI never blocks the
            // main thread on a follow-up SELECT.
            val loadedTasks = stormify.findAll<Task>().onEach { it.user?.let { u -> stormify.populate(u) } }
            val loadedUsers = stormify.findAll<User>()
            loadedTasks to loadedUsers
        }
        _state.value = TasksUiState(tasks = tasks, users = users)
    }

    fun toggleCompleted(task: Task) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            task.isCompleted = !task.isCompleted
            stormify.update(task)
        }
        refresh()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        withContext(Dispatchers.IO) { stormify.delete(task) }
        refresh()
    }

    fun addTask(title: String, description: String, priority: Priority, owner: User) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                stormify.transaction {
                    create(Task().apply {
                        this.title = title
                        this.description = description
                        this.priority = priority
                        this.user = owner
                    })
                }
            }
            refresh()
        }
}

/** Immutable snapshot of what the Tasks screen needs to render. */
data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val users: List<User> = emptyList(),
)
