package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Boss Scheduler front end.
 *
 * On register it makes sure `boss-sched` is installed and its launchd/systemd
 * service is running (downloading the pinned release when nothing is
 * installed). It never runs the daemon itself: the scheduler has to keep
 * firing jobs while BOSS is closed, so it belongs to the OS service manager.
 *
 * Registers a sidebar panel (status, jobs, history) and `scheduler_*` MCP tools.
 */
class SchedDynamicPlugin : DynamicPlugin {

    override val pluginId = SchedServices.PLUGIN_ID
    override val displayName = "Boss Scheduler"
    override val version = "1.0.0"
    override val description =
        "Installs and starts Boss Scheduler, the always-on job scheduler, and shows its jobs and run history"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-scheduler"

    private var services: SchedServices? = null

    override fun register(context: PluginContext) {
        val services = SchedServices(context).also { this.services = it }
        services.start()

        context.panelRegistry.registerPanel(SchedPanelInfo) { ctx, panelInfo ->
            SchedPanelComponent(ctx, panelInfo, services)
        }
        runCatching { context.registerMcpToolProvider(SchedMcpToolProvider(pluginId, services.engine)) }
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
