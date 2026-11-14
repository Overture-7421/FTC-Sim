/**
 * The robot profile: what the robot is made of outside its code, as a JSON file. {@link
 * dev.ftcsim.simulator.profile.RobotProfile} is the model, {@link dev.ftcsim.simulator.profile.RobotProfileLoader}
 * reads it strictly (an unknown property, a missing one or a duplicate key is an error that names the place in the
 * file), and {@link dev.ftcsim.simulator.profile.MotorCatalog} lists the motors a profile can name. The format and
 * every setting are described in {@code docs/robots/robot-profile.md}; the profile that ships is
 * {@code hornet-2026.json} in this module's resources.
 */
package dev.ftcsim.simulator.profile;
