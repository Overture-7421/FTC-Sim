/**
 * Smart cameras: models of devices that are computers on their own and speak HTTP, such as the Limelight 3A. A model
 * answers the requests the SDK's driver sends, with the text the real camera would reply, and builds that text from
 * what its image would show: where the chassis and the tags are, what stands in the way, the sensor's noise and
 * the camera's own pose estimate. {@link dev.ftcsim.hardware.camera.CameraRack} is the
 * {@link dev.ftcsim.contracts.hardware.CameraWire} the SDK adapter talks to, and
 * {@link dev.ftcsim.hardware.camera.CameraModels} lists the cameras there are.
 */
package dev.ftcsim.hardware.camera;
