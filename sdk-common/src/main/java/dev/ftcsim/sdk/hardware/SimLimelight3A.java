package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.limelightvision.LLFieldMap;
import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.CalibrationResult;
import com.qualcomm.hardware.limelightvision.LLStatus;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.hardware.limelightvision.LimelightAccess;
import com.qualcomm.robotcore.util.SerialNumber;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.hooks.Hooks;
import java.net.InetAddress;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The SDK's {@link Limelight3A} with its network and its polling thread taken out and everything else kept: the
 * same states, the same limits and the same answers, with the requests going to the simulated camera over a
 * {@link CameraWire} and the camera's JSON parsed by the SDK's own classes.
 *
 * <p>The real driver polls from a thread of its own at a fixed rate, which would make what robot code reads depend on
 * the operating system's scheduling. Here the polls are emulated in simulated time: after {@link #start} a poll falls
 * due every poll interval, and whenever robot code looks at the driver, the latest poll whose answer has arrived is
 * the one the thread would have left behind. Requests that the real driver makes on the calling thread, such as a
 * pipeline switch, block the calling thread for as long as the camera takes to answer, in simulated time; a request
 * that nobody answers costs the driver's connection timeout.
 */
final class SimLimelight3A extends Limelight3A {

    private static final int STOPPED = 0;
    private static final int STARTING = 1;
    private static final int RUNNING = 2;

    private static final long ISCONNECTED_THRESHOLD_MILLIS = 250;
    private static final long CONNECTION_TIMEOUT_NANOS = 100_000_000L;
    private static final int PYTHON_INPUT_MAX_SIZE = 32;
    private static final int MIN_POLL_RATE_HZ = 1;
    private static final int MAX_POLL_RATE_HZ = 250;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final String name;
    private final String connectionInfo;
    private final CameraWire wire;
    private final boolean plugged;

    private int state = STOPPED;
    /** The driver's executor has been shut down by {@link #stop}; the next {@link #start} makes a new one. */
    private boolean executorShutdown;
    /** The polling task has been scheduled on the executor, and stays scheduled through {@link #pause}. */
    private boolean scheduled;
    private long pollIntervalMillis = 10;
    private long anchorNanos;
    private long spacingNanos;
    private long runningSinceNanos;
    private long processedTick = -1;
    private long getLatencyNanos;
    private LLResult latestResult;
    private boolean updated;
    private long lastUpdateNanos;
    private Long epochMillis;

    SimLimelight3A(SerialNumber serialNumber, String name, InetAddress address, CameraWire wire, boolean plugged) {
        super(serialNumber, name, address);
        // The real constructor only creates the executor, which starts no thread until a task is scheduled on it.
        super.stop();
        this.name = name;
        this.connectionInfo = serialNumber.toString();
        this.wire = wire;
        this.plugged = plugged;
    }

    // ---------------------------------------------------------------------------------------------
    // The polling thread, in simulated time
    // ---------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        long now = System.nanoTime();
        catchUp(now);
        if (state != STOPPED) {
            return;
        }
        state = STARTING;
        if (executorShutdown) {
            executorShutdown = false;
            scheduled = false;
        }
        if (!scheduled) {
            anchorNanos = now;
            spacingNanos = pollIntervalMillis * NANOS_PER_MILLI;
            processedTick = -1;
            scheduled = true;
        }
        state = RUNNING;
        runningSinceNanos = now;
    }

    @Override
    public synchronized void pause() {
        catchUp(System.nanoTime());
        if (state == RUNNING) {
            state = STOPPED;
        }
    }

    @Override
    public synchronized void stop() {
        catchUp(System.nanoTime());
        state = STOPPED;
        scheduled = false;
        executorShutdown = true;
    }

    @Override
    public boolean isRunning() {
        return state == RUNNING;
    }

    @Override
    public synchronized void setPollRateHz(int rateHz) {
        catchUp(System.nanoTime());
        if (state == RUNNING) {
            return;
        }
        int clamped = Math.max(MIN_POLL_RATE_HZ, Math.min(rateHz, MAX_POLL_RATE_HZ));
        this.pollIntervalMillis = 1000 / clamped;
    }

    /**
     * Runs the poll that the driver's thread would have made last: the newest tick of the schedule at which the
     * driver was running and whose answer has arrived by {@code now}. Earlier polls only ever replaced one another.
     */
    private synchronized void catchUp(long now) {
        if (state != RUNNING || !scheduled || !plugged || now < anchorNanos) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            long tick = Math.floorDiv(now - getLatencyNanos - anchorNanos, spacingNanos);
            long firstTickWhileRunning = Math.floorDiv(runningSinceNanos - anchorNanos + spacingNanos - 1, spacingNanos);
            if (tick < 0 || tick < firstTickWhileRunning || tick <= processedTick) {
                return;
            }
            long tickNanos = anchorNanos + tick * spacingNanos;
            CameraWire.Reply reply = wire.get(name, "/results", tickNanos);
            if (reply.reachable() && reply.latencyNanos() > getLatencyNanos) {
                // The answer has not arrived yet at this instant; look again with the latency the camera really has.
                getLatencyNanos = reply.latencyNanos();
                continue;
            }
            processedTick = tick;
            JSONObject json = reply.ok() ? parse(reply.body()) : null;
            if (json != null) {
                long arrival = tickNanos + reply.latencyNanos();
                latestResult = LimelightAccess.parseResult(json, wallMillis(arrival));
                lastUpdateNanos = arrival;
                updated = true;
            }
            return;
        }
    }

    @Override
    public long getTimeSinceLastUpdate() {
        long now = System.nanoTime();
        catchUp(now);
        return updated ? Math.floorDiv(now, NANOS_PER_MILLI) - Math.floorDiv(lastUpdateNanos, NANOS_PER_MILLI) : System.currentTimeMillis();
    }

    @Override
    public boolean isConnected() {
        return getTimeSinceLastUpdate() <= ISCONNECTED_THRESHOLD_MILLIS;
    }

    @Override
    public LLResult getLatestResult() {
        long now = System.nanoTime();
        catchUp(now);
        synchronized (this) {
            if (latestResult == null) {
                latestResult = LimelightAccess.parseResult(new JSONObject(), wallMillis(now));
            }
            return latestResult;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requests the driver makes on the calling thread
    // ---------------------------------------------------------------------------------------------

    @Override
    public LLStatus getStatus() {
        JSONObject status = getJson("/status");
        return status == null ? new LLStatus() : LimelightAccess.status(status);
    }

    @Override
    public boolean reloadPipeline() {
        return post("/reload-pipeline", null);
    }

    @Override
    public boolean pipelineSwitch(int index) {
        return post("/pipeline-switch?index=" + index, null);
    }

    @Override
    public boolean captureSnapshot(String snapname) {
        return post("/capture-snapshot?snapname=" + snapname, null);
    }

    @Override
    public boolean deleteSnapshots() {
        return delete("/delete-snapshots");
    }

    @Override
    public boolean deleteSnapshot(String snapname) {
        return delete("/delete-snapshot?snapname=" + snapname);
    }

    @Override
    public boolean updatePythonInputs(
            double input1, double input2, double input3, double input4, double input5, double input6, double input7, double input8) {
        return updatePythonInputs(new double[] {input1, input2, input3, input4, input5, input6, input7, input8});
    }

    @Override
    public boolean updatePythonInputs(double[] inputs) {
        if (inputs == null || inputs.length == 0 || inputs.length > PYTHON_INPUT_MAX_SIZE) {
            return false;
        }
        try {
            return post("/update-pythoninputs", new JSONArray(inputs).toString());
        } catch (JSONException invalid) {
            return false;
        }
    }

    @Override
    public boolean updateRobotOrientation(double yaw) {
        try {
            return post("/update-robotorientation", new JSONArray(new double[] {yaw, 0, 0, 0, 0, 0}).toString());
        } catch (JSONException invalid) {
            return false;
        }
    }

    @Override
    public boolean uploadPipeline(String jsonString, Integer index) {
        return post("/upload-pipeline" + (index != null ? "?index=" + index : ""), jsonString);
    }

    @Override
    public boolean uploadFieldmap(LLFieldMap fieldmap, Integer index) {
        if (!fieldmap.isValid()) {
            return false;
        }
        return post("/upload-fieldmap" + (index != null ? "?index=" + index : ""), LimelightAccess.fieldMapJson(fieldmap));
    }

    @Override
    public boolean uploadPython(String pythonString, Integer index) {
        return post("/upload-python" + (index != null ? "?index=" + index : ""), pythonString);
    }

    @Override
    public CalibrationResult getCalDefault() {
        return LimelightAccess.calibration(getJson("/cal-default"));
    }

    @Override
    public CalibrationResult getCalFile() {
        return LimelightAccess.calibration(getJson("/cal-file"));
    }

    @Override
    public CalibrationResult getCalEEPROM() {
        return LimelightAccess.calibration(getJson("/cal-eeprom"));
    }

    @Override
    public CalibrationResult getCalLatest() {
        return LimelightAccess.calibration(getJson("/cal-latest"));
    }

    @Override
    public void shutdown() {
        stop();
    }

    @Override
    public void close() {
        stop();
    }

    // ---------------------------------------------------------------------------------------------
    // Identity
    // ---------------------------------------------------------------------------------------------

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.LimelightVision;
    }

    @Override
    public String getDeviceName() {
        return name;
    }

    @Override
    public String getConnectionInfo() {
        return connectionInfo;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {}

    // ---------------------------------------------------------------------------------------------
    // The wire
    // ---------------------------------------------------------------------------------------------

    private JSONObject getJson(String path) {
        long now = System.nanoTime();
        catchUp(now);
        CameraWire.Reply reply = wire.get(name, path, now);
        spend(reply);
        return reply.ok() ? parse(reply.body()) : null;
    }

    private boolean post(String path, String body) {
        long now = System.nanoTime();
        catchUp(now);
        CameraWire.Reply reply = wire.post(name, path, body, now);
        spend(reply);
        return reply.ok();
    }

    private boolean delete(String path) {
        long now = System.nanoTime();
        catchUp(now);
        CameraWire.Reply reply = wire.delete(name, path, now);
        spend(reply);
        return reply.ok();
    }

    /** The calling thread waits for the answer, or for the connection to time out when nobody answers. */
    private static void spend(CameraWire.Reply reply) {
        Hooks.elapse(reply.reachable() ? reply.latencyNanos() : CONNECTION_TIMEOUT_NANOS);
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** The Control Hub's wall clock at a simulated instant; the clock reads the epoch plus simulated time. */
    private long wallMillis(long nanos) {
        if (epochMillis == null) {
            // Both clocks must be read within one millisecond of simulated time for their difference to be exact.
            for (int attempt = 0; attempt < 4 && epochMillis == null; attempt++) {
                long before = System.nanoTime();
                long wall = System.currentTimeMillis();
                long after = System.nanoTime();
                if (Math.floorDiv(before, NANOS_PER_MILLI) == Math.floorDiv(after, NANOS_PER_MILLI)) {
                    epochMillis = wall - Math.floorDiv(before, NANOS_PER_MILLI);
                }
            }
            if (epochMillis == null) {
                epochMillis = System.currentTimeMillis() - Math.floorDiv(System.nanoTime(), NANOS_PER_MILLI);
            }
        }
        return epochMillis + Math.floorDiv(nanos, NANOS_PER_MILLI);
    }
}
