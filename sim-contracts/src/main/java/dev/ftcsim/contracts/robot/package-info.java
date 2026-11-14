/**
 * The simulator's view of a robot program running inside the SDK world, as plain values so that no SDK type crosses
 * the boundary between the class loaders. {@link dev.ftcsim.contracts.robot.RobotHost} mirrors what a Driver Station
 * can do (choose an OpMode, Init, Start, Stop) and is called from the simulator thread only;
 * {@link dev.ftcsim.contracts.robot.RobotEnvironment} is everything outside the SDK that the simulated robot controller
 * is connected to; {@link dev.ftcsim.contracts.robot.GamepadState} is what one gamepad reports (both gamepads always
 * exist, neutral when nothing is mapped to them); {@link dev.ftcsim.contracts.robot.TelemetrySnapshot} is what the
 * robot program asked the driver station to show.
 */
package dev.ftcsim.contracts.robot;
