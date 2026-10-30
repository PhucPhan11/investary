# "Investary (all)" run configuration starts nothing

## Summary

Running the compound IntelliJ configuration `Investary (all)` did nothing: no backend, no frontend,
no visible error.

## Root Cause

Two problems in the shared `.run/` configurations:

- `Postgres (docker compose)` is a before-launch task of `Backend (dev)` / `Backend (debug)`, but it was
  a Shell Script configuration with "Execute in terminal" on and no interpreter. A terminal-mode shell
  configuration gives IntelliJ no process to wait for, so the before-launch step never finished and the
  compound never started its children.
- An earlier fix for `Cannot find JRE 'temurin-21'` (the SDK on this machine is `azul-21`) cleared the
  Maven general and runner settings. That also removed "Use Maven wrapper", so the configurations fell
  back to the IDE's Maven, which may be the installed one mirrored to the unreachable Nexus (see 0001).

## Prevention

- A Shell Script configuration used as a before-launch task must run as a process
  (`EXECUTE_IN_TERMINAL=false`) and execute a script file with
  `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File` (`infrastructure/start-postgres.ps1`).
  Inline script text with `powershell.exe` as interpreter is not passed as a command: PowerShell starts
  interactively and the task waits forever.
- Maven configurations keep `mavenHomeTypeForPersistence=WRAPPER` and use `jreName=#USE_PROJECT_JDK`,
  never a machine-specific SDK name.

## Detection

Manual review point: when a run configuration does nothing, run its before-launch tasks on their own,
and check `idea.log` (`%LOCALAPPDATA%\JetBrains\<product>\log\idea.log`) for `ExecutionException`.
A diff to `.run/*.xml` that empties `myGeneralSettings` or `myRunnerSettings` should be questioned.

## Evidence

2026-10-30: `idea.log` showed `Cannot find JRE 'temurin-21'` from before the JDK change; after it, the
compound started nothing because the Postgres before-launch task ran in the terminal.
