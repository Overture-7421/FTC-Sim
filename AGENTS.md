# AGENTS.md

Rules for anyone, person or AI agent, who changes this repository. The detail is in `docs/development.md`; the reasons are
in `docs/architecture/decisions.md`.

FTC-Sim runs a team's **unmodified** FTC robot project (real FTC SDK 11.x or 12.x) on simulated hardware and a simulated 3D
field. Windows 10/11 x64 only. Two games: DECODE and BIOBUZZ. Java 25, Kotlin Compose window, Jolt physics.

## Read first

1. `docs/README.md`: which page answers what.
2. `docs/architecture/overview.md`: processes, threads, modules, where to look. Read it before touching code.
3. The page for the area you change, then `docs/development.md` and `docs/testing.md`.

## Decisions that are not yours to reverse

Ask the owner before going against any of these.

- **Emulate hardware and the Android environment, nothing else.** The team's code does the rest. Never special-case a
  team's classes (Hornet included).
- **Lockstep virtual time stays**: robot and simulator threads take turns on simulated time. Repeatability and accuracy are
  not goals; testing code and practising are.
- **Fixed robots, fixed ideal sensors.** A team supplies only a mapping (`ftcsim.json`). No editable mechanisms, no sensor
  settings (`docs/robots/fixed-robots.md`). Mechanisms never push on the chassis.
- **Only DECODE and BIOBUZZ, only Windows.** Java 25 is bundled in the distribution; nothing is downloaded and the user's
  Java is never searched for.
- **Not wanted unless asked:** replay or recording, TOML/YAML files, air drag, a Rust sidecar, foul and ranking rules, an
  opposing robot, features for other seasons.

## Hard rules

- **Never run `gradlew clean`.** Tests and the editor read the `build` folders.
- **Parallel builds need a private build directory and `--project-cache-dir`** (recipe: `docs/development.md`).
- **No real mouse, keyboard, focus or window.** UI tests use synthetic events. Start the real window only as `--self-test`
  or `--no-focus --exit-after=<seconds>`.
- **Leave the machine as you found it.** No stray processes (stop only your own, by id), temp files or build folders, and
  nothing written to the real `%LOCALAPPDATA%\FTC-Sim`.
- **Git:** do not commit, push, branch or change git state unless asked. Default branch is `main`.
- **No wall clock in simulation code** (`nanoTime`, `currentTimeMillis`, `Instant.now`): time arrives as simulated nanoseconds.
- **Search before you write.** Extend or reuse; do not duplicate logic.
- **Modules have one job.** The rules (`moduleRules` in the root `build.gradle.kts`) are enforced; do not widen them. Only
  `dev.ftcsim.contracts.*` is shared between the host and the robot world.
- **Every warning fails the build** (`-Xlint:all -Werror`). Suppress only with a visible reason.
- **A test must be able to fail:** break the code once and watch it fail. No sleeps; no wall-clock assertions.
- **Comments only where the code needs them.** Javadoc for public types and the non-obvious.
- **Docs are part of the change.** Update the page that describes it, add `package-info.java` to a new package, list a new
  page in `docs/README.md`.

## Commands

```text
.\gradlew.bat check                   routine tests, module rules, distribution (about 4 min)
.\gradlew.bat :<module>:check         one module
.\gradlew.bat :app:dist               app\build\dist\FTC-Sim and FTC-Sim.zip
app\build\dist\FTC-Sim\FTC-Sim.cmd --self-test    checks an installation, no window
```

Other SDK releases, Hornet, the fake host and the private-build recipe: `docs/development.md`.

## Before you say you are done

1. `.\gradlew.bat check` is green.
2. Routine validation uses the default SDK. Hornet acceptance and import tests and the full SDK fixture matrix are
   optional: run them only when explicitly requested, not for routine iteration or handover (`docs/development.md`).
3. If you changed `app`, the launcher or the window: `:app:dist` and `--self-test`.
4. Report in about 500 words or less: what changed (by package), commands run with counts, what you could **not** verify,
   decisions the owner should know, open problems. Claim nothing you did not run.
