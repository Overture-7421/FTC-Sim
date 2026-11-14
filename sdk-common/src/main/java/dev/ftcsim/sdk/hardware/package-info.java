/**
 * The SDK's side of the simulated hardware. {@link dev.ftcsim.sdk.hardware.SimHardwareMap} is the SDK's own
 * {@code HardwareMap}, which creates a device the first time robot code asks for it, by type, through the legacy
 * per-type mappings or by enumeration. A {@link dev.ftcsim.sdk.hardware.DeviceProvider} builds one family of devices
 * (motors, servos, digital and analog pins, I2C drivers, the Limelight) out of the SDK's own driver classes, and never
 * decides where a device is wired, which the robot profile does. {@link dev.ftcsim.sdk.hardware.SimHubs} holds the
 * SDK's {@code LynxModule} for each hub, which talks to the emulated hubs through
 * {@link dev.ftcsim.sdk.hardware.SimLynxUsbDevice}, the one piece of the SDK's USB stack that is replaced.
 *
 * <p>Nothing here knows a particular device: what answers on a bus is whatever the profile attached to it
 * ({@code docs/architecture/adding-a-device.md}).
 */
package dev.ftcsim.sdk.hardware;
