/**
 * The seam for adding I2C devices to the simulator. A robot profile names a device by a type id; {@link
 * dev.ftcsim.hardware.device.DeviceModels} turns the type id and the profile's settings into an {@link
 * dev.ftcsim.hardware.i2c.I2cPeripheral} that the robot's hub carries on one of its buses, and the SDK's own driver
 * for the device then talks to it unchanged.
 *
 * <p>A device model sees the world only through a {@link dev.ftcsim.hardware.device.DeviceContext}: where the
 * chassis really is, what a ray meets first, the clock, and the physics steps. The host builds one {@link
 * dev.ftcsim.hardware.device.DeviceHost} per robot, creates the devices with it, and calls
 * {@link dev.ftcsim.hardware.device.DeviceHost#step} after every physics step.
 *
 * <p>The models are in the packages of their makers: {@code gobilda}, {@code rev}, {@code bosch} and
 * {@code digitalchickenlabs}. What several of them share is in {@code odometry}, and {@code scripted} is a chassis
 * that follows a script, for tests and demos. See {@code docs/architecture/adding-a-device.md}.
 */
package dev.ftcsim.hardware.device;
