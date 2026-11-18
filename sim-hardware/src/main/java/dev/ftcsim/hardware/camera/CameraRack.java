package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.hardware.CameraWire;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The robot's Limelight, found by the name robot code asks for it with, behind the {@link CameraWire} that the SDK
 * adapter talks to. The robot has one camera and robot code finds it under any name: the first name that asks is its
 * name. A request for any other name is unanswered, as a request to an address with nothing plugged in would be, and is
 * remembered for {@link #problems()}.
 */
public final class CameraRack implements CameraWire {

    private CameraModel camera;
    private String name;
    private final Set<String> missing = new LinkedHashSet<>();

    /** Plugs the robot's camera in. */
    public CameraRack plug(CameraModel camera) {
        if (this.camera != null) {
            throw new IllegalStateException("the robot already has a camera");
        }
        this.camera = Objects.requireNonNull(camera, "camera");
        return this;
    }

    @Override
    public boolean connect(String cameraName) {
        if (camera != null && (name == null || name.equals(cameraName))) {
            name = cameraName;
            return true;
        }
        missing.add(cameraName);
        return false;
    }

    @Override
    public Reply get(String cameraName, String path, long nowNanos) {
        CameraModel model = modelOf(cameraName);
        return model == null ? Reply.unreachable() : model.get(path, nowNanos);
    }

    @Override
    public Reply post(String cameraName, String path, String body, long nowNanos) {
        CameraModel model = modelOf(cameraName);
        return model == null ? Reply.unreachable() : model.post(path, body, nowNanos);
    }

    @Override
    public Reply delete(String cameraName, String path, long nowNanos) {
        CameraModel model = modelOf(cameraName);
        return model == null ? Reply.unreachable() : model.delete(path, nowNanos);
    }

    private CameraModel modelOf(String cameraName) {
        if (camera != null && cameraName.equals(name)) {
            return camera;
        }
        missing.add(cameraName);
        return null;
    }

    /** AprilTag ids the robot's camera currently reports, whether or not robot code polls it. */
    public List<Integer> visibleTagIds(long nowNanos) {
        return camera == null ? List.of() : camera.visibleTagIds(nowNanos);
    }

    /**
     * Everything that weakens the claim that the robot's camera behaves as its code expects: cameras the code asked
     * for beyond the one the robot has, and requests the camera model does not honour.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (String missingName : missing) {
            problems.add("camera \"" + missingName + "\": robot code asked for it but the robot has "
                    + (name == null ? "no camera" : "one camera, \"" + name + "\"")
                    + ", so it is unplugged: no result is ever valid and every request to it fails");
        }
        if (camera != null) {
            camera.problems().forEach(problem -> problems.add("camera \"" + (name == null ? "Limelight" : name) + "\": " + problem));
        }
        return problems;
    }
}
