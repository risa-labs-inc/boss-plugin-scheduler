package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One instance per activation, shared by the panel and the MCP tools. Every
 * host provider may be null; each use degrades instead of crashing.
 */
class SchedServices(
    private val context: PluginContext?,
    val engine: SchedEngine = SchedEngine(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Off the UI thread: find or install the scheduler, then make sure it runs. */
    fun start() {
        scope.launch { engine.ensure() }
    }

    fun dispose() {
        // Deliberately does NOT stop the daemon: it belongs to launchd/systemd
        // and must outlive BOSS.
        scope.cancel()
    }

    fun toast(message: String, isError: Boolean) {
        runCatching {
            context?.notificationProvider?.showToast(
                message,
                if (isError) NotificationType.ERROR else NotificationType.INFO,
                title = "Boss Scheduler",
            )
        }
    }

    fun copy(text: String) {
        runCatching { context?.clipboardProvider?.setText(text) }
    }

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.scheduler"
    }
}
