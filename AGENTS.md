# AGENTS.md - BOSS Scheduler plugin

A BOSS plugin (`type: "panel"`) that installs and fronts
[Boss Scheduler](https://github.com/risa-labs-inc/BossScheduler) (`boss-sched`).
The plugin is an installer and a UI only. The daemon is a separate process run
by launchd (macOS) or systemd (Linux) so jobs keep firing while BOSS is closed.
Never run `boss-sched daemon` from the plugin, and never stop the service in
`dispose()`.

```bash
./gradlew buildPluginJar   # build/libs/boss-plugin-scheduler-<version>.jar
./gradlew build            # + tests
```

Dev testing: copy the jar to `~/.boss_debug/plugins/` (the dev-mode host loads
that directory). Do not run the app; the user does.

## Layout

```
SchedDynamicPlugin   entry point: panel + MCP provider, kicks off SchedEngine.ensure()
SchedServices        per-activation scope, toasts, clipboard (all providers nullable)
SchedEngine          install/start flow, StateFlows, refresh, job actions
SchedDownloader      fetch SHA256SUMS + archive, verify, extract, atomic rename
SchedArchive         SHA256SUMS parsing, sha256, minimal tar reader (no tar binary needed)
SchedRelease         pinned version, release URL contract, target triples, version compare
SchedPaths           install locations, service-file parsing, PATH merge (pure)
SchedService         launchd / systemd load, reload, restart
SchedExec / SchedCli the only place a process starts; `boss-sched --json` client
SchedJson            parsing of status / list / history output (boss-sched 0.1.0)
SchedPanel*          Compose panel + ViewModel (polls every 7s while composed)
SchedMcpTools        scheduler_* MCP tools
```

## Install flow (on register, off the UI thread)

1. Find a binary: the one the existing service file runs, then PATH, then
   `~/.local/bin/boss-sched`, then `~/.boss/scheduler/bin/boss-sched`.
2. None found: download `SchedRelease.PINNED_VERSION` for this target, verify
   against `SHA256SUMS`, extract to a temp file in the bin dir, chmod 755, run
   `--json version`, then atomic-rename to `~/.boss/scheduler/bin/boss-sched`.
   Never write over an existing binary in place: macOS kills a signed binary
   whose pages change.
3. Plugin-managed binary older than the pin: same download, then restart the
   service. Binaries outside `~/.boss/scheduler/bin` are never replaced.
4. `status` exit 0: done. Otherwise, if there is no service file or it runs a
   different binary, `install-agent` (with the login shell PATH merged in, so
   jobs find agent CLIs even when BOSS was launched from Finder) and reload
   (`launchctl bootout` + `bootstrap`, or `systemctl --user daemon-reload` +
   `enable --now` + `restart`). With a matching file, just load (`bootstrap`, or
   `kickstart` when already loaded). Then poll `status` for up to 10s.
5. Every failure lands in `SchedSetup.Failed` with a readable reason and a
   Retry button. Windows shows "not supported".

## MCP tools

Read-only: `scheduler_status`, `scheduler_list`, `scheduler_history`.
Gated by `scheduler.manage`: `scheduler_run_now`, `scheduler_enable`,
`scheduler_disable`. Adding and removing jobs is deliberately not a tool:
agents use the CLI per BossScheduler's `docs/scheduling-for-agents.md`.
`SchedMcpToolsTest` fails if a mutating tool ships ungated.

## Traps

- The release contract (archive names, single `boss-sched` at the root,
  `SHA256SUMS` in `sha256sum` format) is owned by BossScheduler's
  `release.yml`. Change both together.
- `SchedJson` was read against boss-sched 0.1.0 serde shapes. Bump the pin
  and re-check those shapes together.
- `PanelId` order (59) must match the manifest's `panel.order`.
- The default `:jar` task has a `thin` classifier so it cannot clobber
  `buildPluginJar`.
- Count test results from `build/test-results/test/*.xml`, not from
  "BUILD SUCCESSFUL".

Prose uses spaced hyphens, never em-dashes. Kotlin files end with a newline.
