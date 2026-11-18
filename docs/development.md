# Working on FTC-Sim

How to set up, build, test and hand over work in this repository; read it before your first change. Related: [architecture/overview.md](architecture/overview.md) (what the program is), [architecture/decisions.md](architecture/decisions.md) (why), [testing.md](testing.md), [vscode.md](vscode.md).

## Setup

- Windows 10 or 11, x64. Nothing else is supported.
- Any JDK 17 or newer, only so `gradlew.bat` can start. The build compiles, tests and runs on **JDK 25** (`jdk` in `gradle\libs.versions.toml`, the daemon in `gradle\gradle-daemon-jvm.properties`) and downloads it when missing.
- To import a team's Android project or run Hornet tests, also: an Android SDK (`ANDROID_HOME`, or `-Pftcsim.androidSdk=<folder>`) and an **explicit JDK 17 to 21**, because a 2024 project's own Gradle cannot read Java 25 class files.
  - The acceptance tests find or provision a JDK 21 when a Hornet property is given; `-Pftcsim.jdk=<JDK home>` overrides it.
  - `:project-import:test` defaults to the test JVM's own JDK 25: pass `-Pftcsim.jdk` there.

### Hornet

Hornet (`Overture-7421/FTC-Hornet-2026`) is the real team program the gated tests run; the tests are listed in [testing.md](testing.md).

- Hornet acceptance and import tests are **optional**. Run them only when explicitly requested, not for routine iteration
  or handover; leave the Hornet properties unset for normal checks.
- `-Pftcsim.hornet=<checkout>`: your clone of Hornet. It is hashed before and after, never changed.
- `-Pftcsim.hornetPackage=<folder>`: **a disposable folder that survives between runs.** The first run imports Hornet into it (about a minute, needs the Android SDK and a JDK 17 to 21); later runs reuse it. Never point it at anything you keep.
- Without these properties the Hornet tests are **skipped, not failed**: read the skipped count before you claim anything about Hornet.
- Hornet's scores are not exact. Runs may differ a little, so the tests assert "at least" over a few attempts.

## Commands

Run from the repository root. Add `--console=plain` when a script or an agent reads the output.

```text
.\gradlew.bat check                      # routine tests, module rules, the distribution's tests (about 4 minutes)
.\gradlew.bat checkModuleRules           # only the dependency rules
.\gradlew.bat :sim-core:check            # one module
.\gradlew.bat :sim-core:test --tests "dev.ftcsim.core.kernel.FixedRateWorldTest" --rerun   # one class, even if nothing changed
.\gradlew.bat :acceptance-tests:test -PftcSdk=12.0.0     # optional compatibility check on another SDK
.\gradlew.bat :acceptance-tests:test -Pftcsim.hornetPackage=<folder> [-Pftcsim.hornet=<checkout>]   # with Hornet
.\gradlew.bat :project-import:test -Pftcsim.hornet=<checkout> -Pftcsim.jdk=<JDK 17-21 home>        # Hornet import test only
.\gradlew.bat :app:run                   # the real application: window, worker process, importer
.\gradlew.bat :app:run --args="--exit-after=30 --no-focus"   # the same, closes itself, takes no focus
.\gradlew.bat :desktop-ui:runFake        # the window alone on a fake host, no robot code (--args="--practice" for a fake session)
.\gradlew.bat :app:dist                  # app\build\dist\FTC-Sim (with runtime\) and FTC-Sim.zip; -Pftcsim.runtimeJdk=<JDK 25 home> links another JDK
app\build\dist\FTC-Sim\FTC-Sim.cmd --self-test   # checks an installation without opening a window
.\gradlew.bat :acceptance-tests:test -Pftcsim.runtime=app\build\dist\FTC-Sim\runtime --tests "*RealHost*Hornet*" -Pftcsim.hornetPackage=<folder>   # real-host Hornet tests on the bundled runtime
```

- Results: XML in `<module>\build\test-results\test\`, HTML in `<module>\build\reports\tests\test\`. Read them, do not scrape the console.
- Routine checks use the default SDK (11.1.0). The full SDK fixture matrix (11.0.0, 11.1.0, 11.2.0, 11.2.1, 12.0.0) is
  optional: run it only when explicitly requested, not on every change.
- Gradle skips tasks whose inputs did not change and restores results from its cache. To force a run, add `--rerun` to the task or `--no-build-cache` to the build.
- `--continue` shows every failing task; `--configure-on-demand` configures only the projects a build needs.
- Logs of a real run: `%LOCALAPPDATA%\FTC-Sim\logs\app-*.log` and `worker-*.log`.

## Rules and hazards

### Build and machine

- **Never run `gradlew clean`.** Tests, other builds and the editor read the `build` folders. If one module's output is stale, delete that module's `build` folder while nothing else runs.
- **Side-by-side builds need a private build directory AND `--project-cache-dir`.** Gradle's task history lives in `.gradle`, and a task that reruns deletes the "previous outputs" it recorded, possibly the other build's classes. A separate build directory alone does not help.

  ```text
  .\gradlew.bat --console=plain --configure-on-demand --no-build-cache --project-cache-dir C:\work\mine\gradle-cache -I C:\work\mine\private-build.init.gradle check
  ```

  ```groovy
  // private-build.init.gradle
  allprojects { p ->
      p.layout.buildDirectory.set(new File('C:/work/mine/build/' + (p == p.rootProject ? 'root' : p.path.replace(':', '_'))))
  }
  ```

  The included `build-logic` still builds in `build-logic\build`: do not start two builds at once after changing it. Delete your private folders when you finish.
- **Windows will not replace a jar a process has open.** A leftover test JVM holds its jars and the build cannot rewrite them. Find the process, confirm it is yours, `Stop-Process -Id <pid>`.
- **No real window, mouse, keyboard or focus.** Tests drive the UI with synthetic events inside the process. Start the real window only as `--self-test` or `--no-focus --exit-after=<seconds>`.
- **One robot world per JVM.** The FTC SDK keeps state in singletons and Hornet leaves library threads running, so each acceptance test class gets its own JVM (`forkEvery = 1`, 2 GB).
- **Leave the machine as you found it.** No stray process (stop only yours, by id), private folder or temporary file. A test never writes into the real `%LOCALAPPDATA%\FTC-Sim`.
- **Git:** do not commit, push, branch or change git state unless asked.

### Code

Ask before going against a product-owner decision ([architecture/decisions.md](architecture/decisions.md)).

- **No wall clock in simulation code** (`System.nanoTime`, `currentTimeMillis`, `Instant.now`, `LocalDateTime.now`). Time arrives as an argument, in simulated nanoseconds. Only `RealtimeRunner` touches wall-clock time, and only to decide when simulated time moves; the window and `platform` pace themselves in real time.
- Nothing else is promised about order or repeatability: hash-ordered collections, unseeded randomness and `Math` instead of `StrictMath` are fine where harmless. A field's seed (`FieldSetup.seed`) still decides its layout.
- **No duplicated logic.** Search first, then extend or reuse (`RegisterFilePeripheral`, `PieceReserve`, `Region`, `EntityIds`). Remove duplication you find, or say so.
- **Modules have one job and the rules are enforced.** The `moduleRules` block of the root `build.gradle.kts` lists what each module may depend on; `check` fails on anything else. Do not widen it, move the code. A dependency on another module's test fixtures counts as a dependency on that module. The two games in `games` must not refer to each other (`GamePackagesTest`).
- **A new module needs:** a folder with a `build.gradle.kts` applying `ftcsim.java-conventions`; an `include` in `settings.gradle.kts`; a `module(...)` line in `moduleRules`; an entry in the `module` list of `.vscode\tasks.json`; a `package-info.java` per package; a row in the module table of [architecture/overview.md](architecture/overview.md).
- **Only `dev.ftcsim.contracts.*` is shared** between host and robot world. No SDK type in the host; the host's classes are invisible to the robot world.
- Java everywhere; Kotlin only for the Compose screens of `desktop-ui`. Four-space indent, lines up to 120 columns, records for data, `final` by default.
- **Every warning fails the build** (`-Xlint:all,-serial,-processing -Werror -parameters`; Kotlin warnings are errors). Suppress only with a reason the code shows: `restricted` on classes that exist to make native calls (`WindowsWaitableTimer`, `NativeLibrary`), `removal` on hooks that intercept members the JDK is removing (`NetworkHooks`).
- Comment only what needs it. Javadoc on public types and non-obvious methods.
- **Documentation is part of the change:** update the page that describes it, add a `package-info.java` to a new package, add a new page to [README.md](README.md).
- **Tests** (JUnit 6): **a test must be able to fail; no sleeps.** More in [testing.md](testing.md).

### How the build is put together

- `settings.gradle.kts` lists the modules, lets Gradle download JDK 25 (foojay), and allows only Maven Central and, for `androidx.*`, Google's repository. A project cannot add repositories.
- `build-logic` has three plugins: `ftcsim.java-conventions` (toolchain, `--release 25`, lint, JUnit, test JVM flags), `ftcsim.module-rules` (the dependency rules) and `ftcsim.ftc-sdk` (SDK AARs turned into jars; `sdk-common` compiles against 11.1.0, `acceptance-tests` runs on the release `-PftcSdk` selects).
- `gradle\libs.versions.toml` holds every version, exact: no ranges, no lockfiles. `gradle.properties` gives the daemon 3 GB and enables the build cache and parallel execution.
- What must not depend on something at compile time is passed as a file or system property: `app` gives the worker its jars as a class path file ([architecture/app.md](architecture/app.md)); `acceptance-tests` computes the `-Dftcsim.*Classpath` properties of its test JVMs.
- Third-party notices come from the distribution's jars (`:app:thirdPartyNotices`); a jar with an unknown licence fails the build with its name. `:app:runtimeImage` makes the bundled Java runtime ([architecture/distribution.md](architecture/distribution.md)).

## Recipes

| To | Read | Do |
|---|---|---|
| Add an ideal sensor (I2C model or camera) | [adding-a-device](architecture/adding-a-device.md) | One model class, one registry line (`DeviceModels.standard()`, `CameraModels.standard()`), a `HardwareDirectory.Sensor`, the binding in `I2cDriverProvider`, the rig's attachment. The SDK's own driver is the specification |
| Add a motor | [fixed-robots](robots/fixed-robots.md) | One `MotorCatalog` entry (`simulator\robot`) with its constants and gearbox |
| Map another robot | [fixed-robots](robots/fixed-robots.md), [hornet-fit](robots/hornet-fit.md) | The **Robot** screen writes `ftcsim.json` (schema 2) at the root of the robot project; no code |
| Virtualise another JDK member | `package-info` of `dev.ftcsim.runtime`, [decisions](architecture/decisions.md) | A row in `TimeRules`, `NetworkRules` or `RewriteRules`, a method with the implied descriptor in the hooks class (`contracts.hooks`), a probe and a rewrite test. The rule tests scan the JDK and fail for a missing member |
| Support another FTC SDK release | [sdk-versions](sdk-versions.md) | Add an `ftcSdk...` version to `gradle\libs.versions.toml`, run the fixture suite on it, add it to the table. Keep any difference behind one seam |
| Change the window-worker link | [link](architecture/link.md) | A message that gains, loses or reorders a field bumps `Protocol.VERSION`; update the codec, the package description table and `link.md` together |
| Change what the importer does | [prepared-program](architecture/prepared-program.md) | A change that makes packages come out different or better raises `IMPORTER_REVISION`, so kept packages are made again |
| Change the bundled runtime or the Java major version | [distribution](architecture/distribution.md), [decisions](architecture/decisions.md) | `runtimeModules` in `app\runtime.gradle.kts`, or `jdk` in `gradle\libs.versions.toml` and `gradle\gradle-daemon-jvm.properties` (the only pin); then `:app:dist`, `--self-test`, and the module list in `DistributionLayoutTest`. Real-host Hornet tests on the runtime are optional, only when requested |
| Refit the fixed robot | [hornet-fit](robots/hornet-fit.md) | Edit the constants of `RobotDesign` in `simulator`; the chassis numbers are fitted, do not move them; update the numbers `RobotDesignTest` spells out in the same change; re-run the shot sweep. `HornetAutoRunTest` is optional, only when requested |

## Hand-over checklist

1. `.\gradlew.bat check` is green (it includes the module rules).
2. Use the default SDK for routine validation. The full SDK fixture matrix and Hornet acceptance and import tests are
   optional, only when explicitly requested.
3. Touched `app`, the launcher or the window? Run `:app:dist`, then `FTC-Sim.cmd --self-test` from `app\build\dist\FTC-Sim` with no Java on the PATH and no `JAVA_HOME`: it must run on its own `runtime`.
4. The pages that describe your change are updated, and [README.md](README.md) lists every page.
5. You left no stray process, private build folder or temporary file, and `git status --ignored` shows no ignored file under a `src` folder (a package folder named `build` would be ignored).
6. Your report says what you ran, with counts, and what you could **not** verify. Claim nothing you did not run.

## When something is odd

| You see | Likely cause |
|---|---|
| `Could not execute test class` for a class with no source | Stale class files in `<module>\build\classes` from builds whose history was lost: delete that module's `build` folder (nothing else building) and rerun |
| Hornet or OpenGL tests skipped | The property is missing, or there is no OpenGL 3.3 context. Skipped is not passed |
| An acceptance test runs out of memory | Sessions of a Hornet-like program leak their robot world inside one JVM: keep one test class per JVM |
| A session reports a problem (`SessionStatus.problems`) | The kernel noticed something about the robot thread: [decisions](architecture/decisions.md) |
