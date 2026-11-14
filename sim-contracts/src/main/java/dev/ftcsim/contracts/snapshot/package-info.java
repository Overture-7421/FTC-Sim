/**
 * What a viewer is shown of the physics world: {@link dev.ftcsim.contracts.snapshot.WorldSnapshot} is an immutable view
 * of the world at one fixed step, its bodies ordered by entity id so that equal worlds give equal snapshots, and
 * {@link dev.ftcsim.contracts.snapshot.BodyState} is one rigid body in the field's frame (metres, unit quaternion).
 * Simulated time in a snapshot comes from the step index, never from a wall clock. The kinds of body the simulator
 * itself adds are in {@link dev.ftcsim.contracts.snapshot.BodyKinds}; the kinds of a field's own bodies start at 100.
 */
package dev.ftcsim.contracts.snapshot;
