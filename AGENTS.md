# AGENTS.md

For anyone, person or AI agent, who changes this repository. `docs/development.md` has the detail and the reasons; this
file is what must not be forgotten.

## What this is

FTC-Sim runs a FIRST Tech Challenge team's **unmodified** robot project (the real FTC SDK 11.x or 12.x plus the team's
libraries) on simulated hardware and a simulated 3D field, so that the code can be tested and drivers can practise. Windows
10/11 x64 only. Two games only: DECODE (2025-26) and BIOBUZZ (2026-27). Java 25 with a Kotlin Compose window, Jolt physics.

## Read first

1. `README.md`, then `docs/README.md`, which says which page answers what.
2. `docs/architecture/overview.md` before you touch code: processes, threads, class loaders, modules, where to look.
3. The page of the area you change (`docs/architecture/`, `docs/devices/`, `docs/fields/`, `docs/robots/`).
4. `docs/development.md` for the rules and recipes, `docs/testing.md` for what to run.

## Decisions that are not yours to reverse

Made by the product owner; the reasons are in `docs/architecture/decisions.md`. Ask before going against any of them.

- The simulator emulates **hardware and the Android environment and nothing else**; the team's code does everything
  else. Never special-case a team's classes, Hornet included. A device model reproduces the device with its limits.
- **Determinism is a requirement** (same inputs, same run); **accuracy is not**: it is enough to test code and practise.
- Only DECODE and BIOBUZZ. Mechanisms never push on the chassis. Windows only; JDK 25 forced.
- Not wanted unless asked: replay or recording, TOML or YAML profiles (profiles are JSON), air drag, a Rust sidecar,
  foul and ranking rules, an opposing robot, features for other seasons.

## Commands

```text
.\gradlew.bat check                       all tests, the module rules, the launcher (about 4 minutes)
.\gradlew.bat :<module>:check             one module
.\gradlew.bat :acceptance-tests:test -PftcSdk=12.0.0                  another SDK (11.0.0 11.1.0 11.2.0 11.2.1 12.0.0)
.\gradlew.bat :acceptance-tests:test -Pftcsim.hornetPackage=<folder> [-Pftcsim.hornet=<Hornet checkout>]
.\gradlew.bat :app:dist                   app\build\dist\FTC-Sim and FTC-Sim.zip
app\build\dist\FTC-Sim\FTC-Sim.cmd --self-test       checks an installation without opening a window
```

## Hard rules

- **Never run `gradlew clean`.** Tests, other builds and the editor read the `build` folders.
- **Builds side by side need private environments**: a private build directory *and* `--project-cache-dir` (Gradle's task
  history is shared otherwise and deletes the other build's outputs). The recipe is in `docs/development.md`.
- **No real mouse, keyboard, focus or window.** UI tests use synthetic events inside the process. Start the real window
  only as `--self-test` or `--no-focus --exit-after=<seconds>`.
- **Leave the machine as you found it**: no stray processes (stop only a process you started, by id), private build
  folders or temporary files, and nothing written by a test into the real `%LOCALAPPDATA%\FTC-Sim`.
- **Git**: the repository is `Overture-7421/FTC-Sim` on GitHub, default branch `main`. Do not commit, push, branch or
  change git state unless asked.
- **Determinism in simulation code**: no wall clock (`nanoTime`, `currentTimeMillis`, `Instant.now`), no unseeded
  randomness, no threads of your own, no iteration over hash-ordered collections where order can matter, `StrictMath` for
  `sin`/`cos`/`atan2`/`exp`/`pow`. Time arrives as an argument in simulated nanoseconds.
- **No duplicated logic**: search before you write; extend or reuse what exists.
- **Modules have one job and the rules are enforced** (`moduleRules` in the root `build.gradle.kts`). Do not widen them to
  get something done. Only `dev.ftcsim.contracts.*` is shared between the host and the robot world.
- **Every warning fails the build** (`-Xlint:all -Werror`). Suppress only with a reason that the code makes visible.
- **A test must be able to fail**: break the code on purpose and watch it fail. No sleeps; no wall-clock assertions unless
  that is what the test guards.
- **Comments only where the code needs them.** Javadoc for public types and the non-obvious.
- **Documentation is part of the change**: update the page that describes what you changed, add a `package-info.java` to a
  new package, add a new page to `docs/README.md`.

## Before you say you are done

1. `.\gradlew.bat check` is green.
2. For changes the SDK or a team program can see (robot world, SDK host, hardware, mechanisms, physics, fields, importer, link):
   the acceptance suite with Hornet, and the fixture suite on the other SDK releases. `docs/development.md` lists which.
3. For `app`, `launcher` or the window: `:app:dist` and `--self-test`.
4. Report in at most about 500 words: what you changed (by package), the commands you ran with their counts, what you could
   **not** verify, decisions the owner should know, open problems. Do not claim anything you did not run.

## Where things are

`docs/architecture/overview.md` has the module table with the classes to start from. In short: `sim-contracts` (shared
vocabulary), `sim-core` (virtual-time kernel), `robot-runtime` (class loader and bytecode rewriting), `sdk-common` and
`android-shim` (the SDK host), `sim-hardware` (hubs and devices), `sim-physics`, `sim-mechanisms`, `sim-field`, `game-*`
(the world), `simulator` (a session), `worker` and `sim-link` (the process boundary), `project-import`, `desktop-ui`, `app`,
`launcher`, and `acceptance-tests`. `docs/roadmap.md` lists what is missing.
