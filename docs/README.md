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
| add an ideal sensor, or know how a driver class is bound to one | `architecture/adding-a-device.md`, then `devices/` |
| map a robot, or fit the fixed one | `robots/fixed-robots.md`, `robots/hornet-profile.md` |
| know what a field models, and what it estimates | `fields/decode.md`, `fields/biobuzz.md` |
| change the window, or its 3D view | `architecture/ui.md`, `architecture/viewport-measurements.md` |
| change how the window talks to the simulation | `architecture/link.md` |
| understand the application, its folders and the importer | `architecture/app.md`, `architecture/prepared-program.md` |
| change how FTC-Sim starts, the Java runtime it carries or the zip | `architecture/distribution.md` |
| know which FTC SDK releases are supported | `sdk-versions.md` |
| know what is missing, and what to do next | `roadmap.md` |

## Every page

**Working on the project**

- `development.md`: the commands, the hazards of this build, the rules for code, recipes and the checklist before handing over.
- `testing.md`: the layers of tests, the acceptance suite and the Hornet tests, the slicing tests, how to write a test.
- `vscode.md`: tasks, launch configurations, why the settings are what they are, known limits of the editor setup.
- `roadmap.md`: the known limits by area, and a suggested order for next work.
- `sdk-versions.md`: the SDK matrix with its results, and the rehearsal of Hornet on the SDK 12 template.

**Architecture** (`architecture/`)

- `overview.md`: processes, threads, class loaders, the modules and their rules, units and frames, a glossary.
- `decisions.md`: the stack, virtual time, hardware at the wire, physics, the UI, the worker process, provisioning, game
  pieces, why repeatability is not promised and lockstep time is kept, and the robot world's contact with the machine.
- `adding-a-device.md`: the fixed ideal sensors, how a driver class is bound to one, and how to add the model of a new one.
- `link.md`: the process boundary, the wire format, the threads, the catalogue and the timed match.
- `app.md`: the composition root, the folders under `%LOCALAPPDATA%\FTC-Sim`, opening the link, preparing a project.
- `prepared-program.md`: what the importer does and the package it makes.
- `distribution.md`: the folder and zip, the bundled Java runtime and how it is built, how a start goes, the self test, the licence notices.
- `ui.md`: the shell and its screens, state, threads, input, settings, and the tests that need no window.
- `viewport-measurements.md`: what the 3D view costs and how the rendering approach was chosen.

**Devices** (`devices/`): `pinpoint.md`, `color-sensor.md`, `bno055-imu.md`, `limelight.md`: what each models, the numbers and
where they come from, the one setting a student has (if any), what is not simulated.

**Fields** (`fields/`): `decode.md`, `biobuzz.md`: sources, dimensions, what is physics and what is a rule, estimated
numbers, known gaps, the tests that check them.

**Robots** (`robots/`): `fixed-robots.md` (the fixed robot of each game, the mapping a team supplies, the turret and
`ftcsim.json`), `hornet-profile.md` (how Hornet's drivetrain and shooter were fitted and how to reproduce it).

## How the documentation is kept

- **Code says what and how; pages say why, what was measured and what is left.** Every package has a `package-info.java`
  that says what it is for; Javadoc covers public types and the non-obvious. `architecture/overview.md` says where to start
  in each module.
- **One home for each fact.** Link to the page that owns a fact instead of repeating it. A number that came from a source
  says which; a number that is an estimate says so.
- **A change that alters behaviour updates the page that describes it**, in the same change. A new page is added to this
  index. A page that says "not done" must stay true: remove the line when the thing is done.
- **Counts rot.** When a page gives a test count or a timing, say where and when it was measured.
