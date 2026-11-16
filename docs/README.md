# Documentation

Start with the root `README.md` (what FTC-Sim is, how to use and build it), then pick the row that matches your task.
`AGENTS.md` holds the rules for anyone who changes the code.

| I want to ... | Read |
|---|---|
| see how the parts fit together and where to look | `architecture/overview.md` |
| know why it is built this way | `architecture/decisions.md` |
| build, run, change and hand over work | `development.md` |
| write or run tests | `testing.md` |
| set up VS Code | `vscode.md` |
| add an ideal sensor, or see how a driver class is bound | `architecture/adding-a-device.md`, `devices/` (`pinpoint`, `color-sensor`, `bno055-imu`, `limelight`) |
| map a robot, or see how the fixed one was fitted | `robots/fixed-robots.md`, `robots/hornet-fit.md` |
| know what a field models and estimates | `fields/decode.md`, `fields/biobuzz.md` |
| change the window or its 3D view | `architecture/ui.md`, `architecture/viewport-measurements.md` |
| change how the window talks to the simulation | `architecture/link.md` |
| understand the app, its folders and the importer | `architecture/app.md`, `architecture/prepared-program.md` |
| change startup, the Java runtime or the zip | `architecture/distribution.md` |
| see which FTC SDK releases are supported | `sdk-versions.md` |
| see what is missing and what to do next | `roadmap.md` |

## How the docs are kept

- **Code says what and how; pages say why, what was measured and what is left.** Every package has a `package-info.java`.
- **One home per fact.** Link instead of repeating. Say where a number came from, or that it is an estimate.
- **Change behaviour, change the page**, in the same change. Add a new page to this index. Remove a "not done" line when
  it is done.
- **Counts rot.** Give a test count or timing only with where and when it was measured.
