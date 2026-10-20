package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedMcpToolsTest {

    private val provider = SchedMcpToolProvider(
        SchedServices.PLUGIN_ID,
        SchedEngine(os = SchedOs.UNSUPPORTED, target = null),
    )

    @Test
    fun `every mutating tool needs scheduler-manage`() {
        val tools = provider.tools()
        val mutating = tools.filter { !it.readOnly }
        assertEquals(setOf("scheduler_run_now", "scheduler_enable", "scheduler_disable"), mutating.map { it.name }.toSet())
        mutating.forEach { assertEquals(listOf(SchedMcpToolProvider.PERMISSION_MANAGE), it.requiredPermissions, it.name) }
        assertTrue(tools.none { it.name.contains("remove") || it.name.contains("add") })
    }

    @Test
    fun `tools report not-ready instead of throwing`(): Unit = runBlocking {
        val list = provider.tools().first { it.name == "scheduler_list" }
        val r = list.handler.call(McpToolArgs(emptyMap()))
        assertTrue(r.isError)
        val run = provider.tools().first { it.name == "scheduler_run_now" }
        assertTrue(run.handler.call(McpToolArgs(mapOf("id" to "x"))).isError)
    }
}
