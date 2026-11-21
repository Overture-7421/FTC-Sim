# The installer, the releases and self-update

How a release of FTC-Sim is made and installed, and how an installed FTC-Sim finds and installs a newer one. Read it when you change the release workflow, the installer script, or `dev.ftcsim.app.update`. The unzip-and-run folder these build on is in [distribution.md](distribution.md).

## What a release holds

A GitHub release (tag `v1.2.3`) has three files, made by `.github/workflows/release.yml`:

| File | What |
|---|---|
| `FTC-Sim-<version>-Setup.exe` | The installer (Inno Setup 6, `app/installer/FTC-Sim.iss`): the distribution folder for one Windows user, with shortcuts and an uninstaller. |
| `FTC-Sim-<version>.zip` | `app/build/dist/FTC-Sim.zip` renamed: the folder to unzip and run, as before. |
| `SHA256SUMS.txt` | `<sha256>  <name>` lines for the two files above, as `sha256sum` writes them. |

**Making one.** On GitHub: Actions, *Release*, Run workflow, give a version (`1.2.3`; `1.3.0-rc.1` is published as a pre-release, which installed copies never offer) and leave *Run the whole test suite first* on. That is the only way a release is made: the workflow **creates the tag `v<version>` and the release together**, on the commit it runs on, and a tag pushed by hand makes nothing. It refuses to run anywhere but the default branch, and refuses a version whose tag already exists, before building anything. On a Windows runner it then: runs `check` (unless switched off), builds `:app:dist -PappVersion=<version>`, runs the distribution's `--self-test`, installs Inno Setup (`choco`), compiles the installer, **installs it silently into a temp folder, runs the installed program's `--self-test` and uninstalls it**, writes the checksums and creates the release with the GitHub CLI. Nothing is tagged or published when any step fails. The version is given to Gradle as `-PappVersion` and ends up in `version.properties` inside `app.jar`; every other build is `0.0.0-dev`.

## The installer

- **Per user, no administrator.** `PrivilegesRequired=lowest`, `DefaultDirName={localappdata}\Programs\FTC-Sim`: nothing outside the user's profile, so it works on a school PC where the person is not an administrator, unless a policy forbids programs there ([distribution.md](distribution.md), "What a PC may forbid"). One `AppId`, so a newer installer upgrades the installation in place (it finds the folder in the user's uninstall key).
- **Shortcuts** in the Start menu (and, as an unchecked task, on the desktop) start the bundled `runtime\bin\javaw.exe` directly, so there is no console window, with the options `FTC-Sim.cmd` uses: `runtime-options` as an argument file (the self test checks the running Java was given every one) and the crash report in the user's logs folder. `FTC-Sim.cmd` stays in the folder for scripts and `--self-test`. **A Windows shortcut drops parameters that start with `@`**, found by building the installer and reading the shortcut back, so the argument file is not the first argument (`ReleaseFilesTest` keeps it so).
- **Uninstalling** (Apps & features) removes the program and the installers it fetched for updating. What FTC-Sim keeps for the person, `%LOCALAPPDATA%\FTC-Sim` (settings, logs, prepared programs), stays.
- **Silent runs.** `/SILENT` shows progress and asks nothing; the final `[Run]` entry (start FTC-Sim) also runs in a silent install, which is how an update restarts the program. `/NORUN=1` leaves it out, for the workflow's test and for tests like it.
- **Not signed.** Windows SmartScreen may warn once about an unknown publisher when the installer is first run from a download; "More info", "Run anyway". A signature needs a certificate that FTC-Sim does not have. Inno Setup is free for non-commercial use (the compiler says so); a commercial use needs its licence.
- **Verified here (Inno Setup 6.7.4, a temp folder, nothing else touched):** the script compiles (82 MB for 98 MB of files); a silent install into a chosen folder passes the installed `--self-test`; the Start menu shortcut's target and arguments are right and the real application starts from them and exits with `--exit-after`; a second run with `/SILENT` and no folder replaces the files in the same folder; the uninstaller removes the program, the shortcut and the uninstall key.

## Self-update

`dev.ftcsim.app.update` and the window's banner. Only a build made for a release (version not `0.0.0-dev`) takes part; a development build, a test and `:app:run` never look.

1. **Look.** At start (off the UI thread, unless `--no-update-check`) `GitHubUpdater.checkNow` reads `https://api.github.com/repos/Overture-7421/FTC-Sim/releases/latest` (GitHub's "latest" is the newest published release that is neither a draft nor a pre-release). It needs the release's `FTC-Sim-<version>-Setup.exe` and `SHA256SUMS.txt` and a version newer than its own (`Version`: `1.2.10 > 1.2.9`, `1.5.0-rc.1 < 1.5.0`). Any failure (no network, a school proxy, no release, a rate limit) is one line in the log and nothing in the window.
2. **Offer.** A bar above the screens says "FTC-Sim 1.2.0 is available", with *Update and restart* and *Later*. Nothing is fetched or installed unasked, and *Later* hides the offer until the next start.
3. **Fetch and check.** *Update and restart* downloads the installer into `%LOCALAPPDATA%\FTC-Sim\updates` with a progress percentage, computes its SHA-256 while it streams, compares it with the line for that file in `SHA256SUMS.txt` of the same release, and keeps the file only if they match; a mismatch, a missing line or a failed download deletes it and shows "The update did not work: ..." with *Try again* and *Dismiss*. Older installers in that folder are removed first.
4. **Install.** The window closes (its link and worker end as on any close), and only then, from `Main`, the installer is started and not waited for: `Setup.exe /SILENT /NOCANCEL /SUPPRESSMSGBOXES /CLOSEAPPLICATIONS`. It replaces the files in the same folder and starts the new FTC-Sim.

**The invariant (a bug fixed once).** `App.run` must *return*: Compose's `application` ends the process when it finishes unless `exitProcessOnExit = false`, and `Main`'s tail (closing the host, starting the installer) never ran, so "Update and restart" only closed the window. `AppExitTest` guards the flag. The update check and the install run on the window's daemon `io` threads, because the application returns only when its coroutines have ended. Installations from before the fix (0.0.2, 0.0.3) cannot update themselves: install the first fixed release by hand once. The log shows "fetched ... checksum matches", "window closed" and "starting its installer".

**What it will and will not fetch.** HTTPS on `github.com`, `*.github.com` and `*.githubusercontent.com` only (a release asset is a redirect to the last); plain HTTP only to the computer itself, when the API address is, which is how the tests stand in for GitHub. An address in the release JSON that is elsewhere is not followed.

**What the checksum is worth.** It comes from the same release as the installer, so it catches a damaged or truncated download and a mismatched pair, **not** a release that someone with write access to the repository replaced: the installer is not signed. Anyone who can publish a release can publish an update.

**Tests** (`:app:test`, `:desktop-ui:test`; no real network, no real installer, no real window): `VersionTest` (ordering, pre-releases, refusal, the development version), `GitHubUpdaterTest` (a stand-in GitHub on the loopback address: offer, not newer, offline, no installer, a host that is not GitHub, a pre-release, fetch and keep, the installer only starting after the window closed, a wrong or missing checksum refused and deleted, try again, dismiss, old installers removed, the installer's options; mutation checked: without the checksum comparison the wrong-checksum test fails), `ReleaseFilesTest` (the installer script and the workflow against `GitHubUpdater`'s names), `UpdateBannerTest` (what the bar says and which choices it gives, with synthetic clicks), `AppOptionsTest`.

**Not verified:** a real release on GitHub (the repository has none yet; the first run of the workflow is the check, and its steps try the installer before publishing), the update from a real release end to end on a PC, GitHub's API limit of 60 requests an hour per address for a school that shares one address (a failed look is silent, and it looks once per start), and SmartScreen's reaction to the installer.

## Changing it

| I want to ... | Do |
|---|---|
| Rename the installer or the checksums file | `GitHubUpdater.INSTALLER_NAME` / `CHECKSUMS_NAME`, `FTC-Sim.iss` (`OutputBaseFilename`), the workflow; `ReleaseFilesTest` fails until they agree |
| Make the application not look | `--no-update-check`, or build without `-PappVersion` |
| Point at another repository | `GitHubUpdater.REPOSITORY`, `AppUpdatesURL` and the URLs in the script |
| Sign the installer | Sign `Setup.exe` in the workflow (Inno's `SignTool`), and have the updater check the signature before running it |
