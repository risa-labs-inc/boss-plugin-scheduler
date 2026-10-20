package ai.rever.boss.plugin.dynamic.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Panel state and actions. Its own scope, so a closed panel cancels its work;
 * the shared state lives in [SchedEngine].
 */
class SchedPanelViewModel(private val services: SchedServices) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine get() = services.engine

    val setup: StateFlow<SchedSetup> = engine.setup
    val daemon: StateFlow<SchedDaemon> = engine.daemon
    val jobs: StateFlow<List<SchedJob>> = engine.jobs
    val jobsError: StateFlow<String?> = engine.jobsError
    val selected: StateFlow<String?> = engine.selected
    val history: StateFlow<List<SchedRun>> = engine.history

    private val _pendingRemove = MutableStateFlow<String?>(null)

    /** Job id awaiting a second click to confirm removal. */
    val pendingRemove: StateFlow<String?> = _pendingRemove.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val homePath: String get() = engine.schedHome.absolutePath

    /**
     * Refresh every [POLL_MS] for as long as the caller (the panel's
     * LaunchedEffect) is active, i.e. while the panel is composed. Polling
     * because the CLI has no push channel; each tick is two short `boss-sched`
     * calls (status, list) plus one for history when a job is selected.
     */
    suspend fun pollWhileVisible() {
        while (true) {
            engine.refresh()
            delay(POLL_MS)
        }
    }

    fun refresh() {
        scope.launch { engine.refresh() }
    }

    fun retrySetup() {
        scope.launch { engine.ensure() }
    }

    fun select(id: String) {
        scope.launch { engine.select(if (engine.selected.value == id) null else id) }
    }

    fun act(action: SchedEngine.Action, id: String) {
        if (action == SchedEngine.Action.REMOVE && _pendingRemove.value != id) {
            _pendingRemove.value = id
            return
        }
        _pendingRemove.value = null
        scope.launch {
            _busy.value = "${action.name.lowercase().replace('_', '-')} $id"
            try {
                val (message, isError) = engine.act(action, id)
                services.toast(message, isError)
            } finally {
                _busy.value = null
            }
        }
    }

    fun cancelRemove() {
        _pendingRemove.value = null
    }

    fun copy(text: String) {
        services.copy(text)
        services.toast("Copied", false)
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        const val POLL_MS = 7_000L
    }
}
