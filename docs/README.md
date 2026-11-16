# Documentation

Start with the root `README.md` (what FTC-Sim is, how to use and build it), then take the row below that matches what you
are about to do. `AGENTS.md` at the root is the short version of the rules for anyone, person or agent, who changes the
code.

## I want to ...

| I want to ... | Read |
|---|---|
| see how the parts fit together, and where to look for what | `architecture/overview.md` |
| know why it is built this way | `architecture/decisions.md` |
| build, run, change and hand over work safely | `development.md` |
| write or run tests; know what each layer proves | `testing.md` |
| set up VS Code | `vscode.md` |
| add an ideal sensor, or know how a driver class is bound to one | `architecture/adding-a-device.md`, then `devices/pinpoint.md`, `devices/color-sensor.md`, `devices/bno055-imu.md`, `devices/limelight.md` |
| map a robot, or fit the fixed one | `robots/fixed-robots.md`, `robots/hornet-profile.md` |
| know what a field models, and what it estimates | `fields/decode.md`, `fields/biobuzz.md` |
| change the window, or its 3D view | `architecture/ui.md`, `architecture/viewport-measurements.md` |
| change how the window talks to the simulation | `architecture/link.md` |
| understand the application, its folders and the importer | `architecture/app.md`, `architecture/prepared-program.md` |
| change how FTC-Sim starts, the Java runtime it carries or the zip | `architecture/distribution.md` |
| know which FTC SDK releases are supported | `sdk-versions.md` |
| know what is missing, and what to do next | `roadmap.md` |

## How the documentation is kept

- **Code says what and how; pages say why, what was measured and what is left.** Every package has a `package-info.java`
  that says what it is for; Javadoc covers public types and the non-obvious. `architecture/overview.md` says where to start
  in each module.
- **One home for each fact.** Link to the page that owns a fact instead of repeating it. A number that came from a source
  says which; a number that is an estimate says so.
- **A change that alters behaviour updates the page that describes it**, in the same change. A new page is added to this
  index. A page that says "not done" must stay true: remove the line when the thing is done.
- **Counts rot.** When a page gives a test count or a timing, say where and when it was measured.
