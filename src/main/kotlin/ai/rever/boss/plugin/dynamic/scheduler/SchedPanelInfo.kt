package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule

object SchedPanelInfo : PanelInfo {
    /** Id and order must match the manifest's `panel` block exactly, or `openPanel` never finds it. */
    override val id = PanelId("scheduler-panel", 59)
    override val displayName = "Scheduler"
    override val icon = Icons.Outlined.Schedule
    override val defaultSlotPosition = left.bottom
}
