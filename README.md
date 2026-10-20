# BOSS Scheduler Plugin

Install, start and watch [Boss Scheduler](https://github.com/risa-labs-inc/BossScheduler)
(`boss-sched`) from BOSS.

Boss Scheduler is a small always-on job scheduler. It runs as its own service under launchd
(macOS) or systemd (Linux), so scheduled jobs keep firing when BOSS is closed. This plugin is
the installer and the UI for it: it never runs the scheduler inside BOSS, and disabling the
plugin does not stop the service.

## What it does

**On load** (off the UI thread)

- Finds an existing `boss-sched` (the one the service file runs, then `PATH`, then
  `~/.local/bin`, then `~/.boss/scheduler/bin`).
- If none is found, downloads the pinned release for this platform, verifies it against the
  release's `SHA256SUMS`, and installs it to `~/.boss/scheduler/bin/boss-sched` with an atomic
  rename.
- Installs and loads the launchd agent or systemd user unit if it is missing or points at a
  different binary, then waits for the scheduler to report healthy.
- Upgrades a plugin-managed binary that is older than the pin. Binaries installed anywhere
  else are never replaced.

Every failure is shown in the panel with a readable reason and a **Retry** button. Windows is
not supported.

**Sidebar panel** (lower-left slot)

- Scheduler status, version and service state.
- Jobs with their schedule and last result. Run now, enable, disable, or remove a job
  (removal asks for confirmation).
- Recent runs with their status and finish time.

## MCP tools

Agents running in BOSS terminals get `mcp__boss__scheduler_*`:

| Tool | Purpose | Permission |
|---|---|---|
| `scheduler_status` | Scheduler health, version and service state | none |
| `scheduler_list` | Scheduled jobs | none |
| `scheduler_history` | Recent runs of a job | none |
| `scheduler_run_now` | Run a job immediately | `scheduler.manage` |
| `scheduler_enable` / `scheduler_disable` | Turn a job on or off | `scheduler.manage` |

Adding and removing jobs is deliberately not an MCP tool: agents use the `boss-sched` CLI as
described in BossScheduler's `docs/scheduling-for-agents.md`. `SchedMcpToolsTest` fails the
build if a mutating tool ships without the permission.

## Requirements

- macOS or Linux (systemd user session)
- BOSS >= 9.4.2, boss-plugin-api >= 1.0.80

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-scheduler-*.jar ~/.boss/plugins/
```

Then reload from Toolbox. See [AGENTS.md](AGENTS.md) for architecture and conventions.
