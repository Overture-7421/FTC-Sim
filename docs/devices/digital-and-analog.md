# Digital and analog inputs

Robot code reads a limit switch, a beam break or a potentiometer with the SDK's own classes, unchanged:
`hardwareMap.get(TouchSensor.class, "slideLimit")` (the REV Touch Sensor, which a REV magnetic limit switch also is),
`hardwareMap.get(DigitalChannel.class, "beam")` and `hardwareMap.get(AnalogInput.class, "armPot")`. They run on the SDK's
`LynxDigitalChannelController` and `LynxAnalogInputController`, which talk RHSP to the hub emulation
(`DigitalBank` and `AdcBank` in `sim-hardware`, `dev.ftcsim.hardware.lynx`). What a pin reads is up to what the robot
profile connects to it, in the `digitals` and `analogs` sections. The classes are created the first time robot code asks
for them (`DigitalInputProvider` and `AnalogInputProvider` in `sdk-common`), and the SDK source of everything involved
(`RevTouchSensor`, `DigitalChannelImpl`, `AnalogInput`, the two controllers, the DIO and ADC commands) is identical in SDK
11.0.0, 11.1.0, 11.2.0, 11.2.1 and 12.0.0, which is why the adapter has nothing version-specific.

## What is modelled

- **The hub's pins.** Eight digital pins, 0 to 7, power up as inputs. An input that nothing is connected to reads high, as
  the hub's pull-ups make it. A pin set to `OUTPUT` starts low, drives the level robot code gives it (`setState`) and reads
  back what it drives; `setState` on an input is ignored by the SDK itself. A pin that is an output drives its level
  whatever is connected to it: there is no model of two drivers fighting.
- **Four analog inputs**, 0 to 3.3 V, measured by a 12-bit converter and reported to the SDK in whole millivolts, so
  `AnalogInput.getVoltage()` is a multiple of 0.001 V; `getMaxVoltage()` is 3.3. A voltage beyond the range saturates.
- **A switch to ground.** A REV touch sensor or magnetic limit switch connects the pin to ground when it is closed, so the
  pin reads low while it is closed: a closed switch is a `TouchSensor` that `isPressed()` (the SDK defines that as
  `!getState()` of its pin) and a `DigitalChannel` whose `getState()` is `false`, whichever of the two classes the
  program uses for it.
- **Bulk caching.** With `LynxModule.BulkCachingMode.AUTO` or `MANUAL` the SDK reads all pins in one bulk exchange and
  answers each pin from it, so a pin read has the age of the encoder readings next to it.
- **Cost in time.** Every read that is not answered from the bulk cache is an exchange with the hub: about 1.5 ms of
  simulated time on the Control Hub, and 0.8 ms more behind an Expansion Hub (the bus latency model in `BusLatency`;
  these are estimates, not measurements).
- **Unbound pins.** A name the profile does not list still works: it is given the next free channel (the Control Hub's
  first) and reads high, or 0 V. The session reports it as a problem: once for the name, for example `'limit' is not in
  the robot profile, so it was given a port of its own`, and once for each pin that was read with nothing connected, for
  example `hub 173 (Control Hub) digital port 3 was read with nothing connected, so it read high`. A pin the profile lists
  without a `source` is connected but idle, and is not reported. Under bulk caching the hub cannot tell which pin was
  read, so only the first message appears.
- **One name, one device.** As in a robot configuration, a name is a `TouchSensor` or a `DigitalChannel`, not both; asking
  for the other gets the SDK's error, which says what the name already is.

## Profile

```json
"digitals": {                                       // by the name robot code uses
  "slideLimit": { "channel": 0,                      // 0 to 7; "hub" is the parent hub when absent
    "source": { "type": "encoder", "motor": "slide", "counts": 2400, "closedWhen": "ABOVE" } },
  "beam":       { "hub": 2, "channel": 1,
    "source": { "type": "constant", "level": "LOW" } },
  "spare":      { "channel": 2 }                     // connected, nothing drives it: reads high
},
"analogs": {
  "armPot": { "channel": 0,                          // 0 to 3
    "source": { "type": "encoder", "motor": "arm", "voltsAtZero": 0.5, "voltsPerCount": 0.0001 } },
  "reference": { "channel": 1, "source": { "type": "constant", "volts": 1.65 } }
}
```

| Source | Keys | Reads |
|---|---|---|
| digital `constant` | `level`: `HIGH` or `LOW` | that level, always |
| digital `encoder` | `motor`, `counts`, `closedWhen`: `ABOVE` or `BELOW` | LOW (the switch is closed) while the encoder of `motor` is above, or below, `counts`; HIGH otherwise |
| analog `constant` | `volts`, 0 to 3.3 | that voltage, always |
| analog `encoder` | `motor`, `voltsAtZero` (0 to 3.3), `voltsPerCount` | `voltsAtZero + voltsPerCount * counts`, kept within 0 to 3.3 V; a negative slope is a potentiometer wired the other way round |

- `motor` is a motor of the profile's `motors`, by the name robot code uses, a drivetrain wheel included. Every key a
  type does not take, and every key it needs, is named in the error, which says where in the file it is
  (`digitals.slideLimit.source: a 'constant' source takes only level, not 'counts'`).
- **The count is the shaft's.** It is the encoder count since the robot was built, which robot code cannot change: a
  motor's `STOP_AND_RESET_ENCODER` moves the hub's own zero and nothing else. A switch bolted to a slide closes where the
  slide is, whatever the program last called zero.
- **`ABOVE` and `BELOW` are strict.** At exactly `counts` the switch is open.
- **Sampled when the hub is read.** The encoder is read at that moment: the switch has no delay, no bounce and no
  memory, and nothing runs between reads, so the readings do not depend on how simulated time is sliced.
- Two names cannot have the same channel of a hub, a digital and an analog pin may have the same number, and a hub that
  the profile does not declare is an error.

## Tests

`PinSourcesTest` and `PinSourcesOnTheHubTest` (`sim-hardware`) test the sources alone and on a hub, with a motor turning
under them. `RobotProfileLoaderTest` and `RobotRigPinsTest` (`simulator`) test the keys and their messages and that the
rig connects each source to the port the profile names, on either hub. `PinsSessionTest` (`acceptance-tests`) runs the
real SDK classes in OpModes under `src/scenarios`: a lift driven through the real motor classes closes a switch at the
profile's count and a potentiometer follows it, a `DigitalChannel` is set to output and back, bulk caching is on,
names are unbound, and three different slicings of simulated time give the same telemetry, the same world hash and the
same shaft counts. It passes on every SDK of the matrix (`-PftcSdk=11.0.0`, `11.1.0`, `11.2.0`, `11.2.1`, `12.0.0`).

## Not simulated

- Pulse-width inputs (a sensor that answers with a pulse length), edge counting and interrupts: a `DigitalChannel` is
  polled, as on the robot.
- Contact bounce and debounce, hysteresis, switching delay, a Hall sensor's field, the pull-up's resistance, wiring
  resistance and noise on an analog line, and the potentiometer's travel and wear. A potentiometer simply saturates at
  the ends of the range.
- What an output does: nothing in the world reads a digital output, so a `DigitalChannel` set to output only changes what
  it reads back, and an LED indicator (`hardwareMap.get(LED.class, ...)`) is not made. The hardware panel of the window
  does not show the pins either.
- The SDK resets every digital pin to input when an OpMode is initialised, because a real robot's controller is in the
  hardware map; the simulator's is not, so a pin that one OpMode leaves as an output is still an output in the next.
  Set the mode in `init`, as the SDK's sample does.
- The Modern Robotics touch sensor and optical distance sensor, which need the legacy module.
