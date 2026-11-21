# Digital Chicken Labs OctoQuad

The OctoQuad FTC Edition (MK1 and MK2) reads up to eight encoders over I2C. On the BIOBUZZ robot it reads the turret's two REV Through Bore Encoders. Code gets it as `hardwareMap.get(OctoQuad.class, name)`, under any name, and it is the SDK's own `OctoQuadImpl` talking to a model of the board at I2C address 0x30 (`OctoQuadModel`, `dev.ftcsim.hardware.device.digitalchickenlabs`), on bus 3 of the Control Hub.

**It exists only on a robot whose design puts encoders on it** (the turret encoders of BIOBUZZ's profile, [fixed-robots](../robots/fixed-robots.md)). On any other robot the lookup fails with a message and the session lists it as unsupported, as before.

## What is simulated

| Part | Behaviour |
|---|---|
| Identity | Chip id 0x51; firmware 3.1.0 by default (`fw.major`, `fw.minor`, `fw.engineering`). The SDK 12 driver is built for 3.1, so an SDK 11 driver (3.0) prints "newer minor revision", as it does on a real board. |
| Encoders | BIOBUZZ's two, on ports 0 and 1. Their angle is the turret's true angle times `turnsPerTurretTurn`, so they follow the servo or motor that turns the turret. A port with no encoder reads 0. |
| Bank configuration | `setChannelBankConfig`: `ALL_PULSE_WIDTH` returns the width in microseconds of the absolute pulse; `ALL_QUADRATURE` returns incremental counts, 8192 to a turn of the encoder; the mixed bank settings follow the banks of four. A board with an encoder on it starts absolute, as if configured and saved to flash. |
| Pulse width | A V2 encoder's pulse runs 3.884 to 998.06 microseconds over a turn, a V1's 1 to 1024. The model returns the whole microsecond. `setSingleChannelPulseWidthParams` sets the minimum and maximum, which matter only to wrap tracking. `setSingleChannelPulseWidthTracksWrap` makes the position the pulse plus the wraps times the span; a reset zeros the wraps. |
| Direction | `setSingleEncoderDirection` and `setAllEncoderDirections` reverse a port; so does an encoder's `mirrored` in the profile (the real encoder's direction is not known from its data sheet, so check it on the robot). |
| Velocity | The change of position over the sample interval (default 50 ms, `setSingleVelocitySampleInterval`), sampled on the simulator's steps, so an interval is a little over what was asked. |
| Resets, parameters | `resetSinglePosition`, `resetAllPositions`, `resetMultiplePositions`; parameters read back as written; `saveParametersToFlash` keeps them for `resetEverything`; uptime is the simulated clock. The flash does not outlive the session. |
| Reads | The positions and velocities come with the CRC16 the driver checks (PROFIBUS polynomial, `readAllEncoderData` and the caching reads). |

## Not modelled

- The MK2's IMU and the localizer (`setLocalizer...`, `readLocalizerData`): the registers and parameters take what is written and report a localizer that is not initialised, with a valid CRC.
- Noise on the pulse: the real encoders vary by about 10 microseconds, which the model leaves out. A team's minimum and maximum therefore only matter to wrap tracking.
- The PWM refresh rate (unknown), and the bus time of a read.
- I2C recovery modes (stored, never used) and the flash across sessions.

## Verified by

- `OctoQuadModelTest` (`sim-hardware`): registers, CRC, pulse widths, offset and direction, quadrature, wrap tracking, velocity, parameters, flash, uptime and refused settings.
- `BiobuzzStubSessionTest` (`acceptance-tests`): the stand-in program reads the two encoders through the SDK driver, decodes the turret angle with the vernier of the two gear ratios and matches the true angle within a degree over the turret's range ([biobuzz-stub](../robots/biobuzz-stub.md)).
