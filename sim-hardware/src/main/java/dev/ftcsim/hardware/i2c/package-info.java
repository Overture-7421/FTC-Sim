/**
 * What a device on a hub's I2C bus implements. The hub owns the bus timing and the datagrams; a peripheral only
 * sees the bytes a real device would see on the wire, together with the simulated time of the access.
 */
package dev.ftcsim.hardware.i2c;
