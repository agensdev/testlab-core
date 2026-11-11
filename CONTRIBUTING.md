# Contributing

Thanks for helping. A few rules keep the core usable by every team:

- **Nothing organisation-specific in the core.** Repos, Jira projects and labels, deeplinks, login
  flows, report formats and app vocabulary belong in a profile ([docs/PROFILES.md](docs/PROFILES.md)).
  If the core needs a new knob, add a field to `core/…/profile/Profile.kt` (with a default that
  keeps today's behaviour), check it in `ProfileCheck.kt`, document it in PROFILES.md and run
  `UPDATE_SCHEMA=1 ./gradlew :core:jvmTest` to refresh `docs/profile.schema.json`.
  `scripts/scrub-audit.sh` (also in CI) catches the names this project started with.
- **Tests with every change.** `./gradlew check` must stay green; device behaviour is covered by
  the fakes in `device/src/testFixtures` and the e2e scripts in `scripts/dev` (see [docs/DEV.md](docs/DEV.md)).
- **Texts in English.** Texts for people go through `tr("…")` (placeholders `{0}`, `{app}`); texts
  for Claude (tool descriptions and results) are plain strings.
- **Secrets never leave the vault**: not in results, logs, traces, reports or profiles.
- **Writes outside the computer** (Jira, uninstalls, starting CI builds) go through
  `LabRuntime.confirmWrite`, so the user sees the exact payload first.

Open an issue before a large change. By contributing you agree that your work is licensed under
the Apache License 2.0.
