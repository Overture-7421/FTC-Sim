package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.LynxUsbDevice;
import com.qualcomm.hardware.lynx.LynxUsbDeviceImpl;
import com.qualcomm.hardware.lynx.commands.LynxDatagram;
import com.qualcomm.hardware.lynx.commands.LynxMessage;
import com.qualcomm.hardware.lynx.commands.standard.LynxKeepAliveCommand;
import com.qualcomm.robotcore.eventloop.SyncdDevice;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.exception.RobotCoreException;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.LynxModuleDescription;
import com.qualcomm.robotcore.hardware.LynxModuleMeta;
import com.qualcomm.robotcore.hardware.LynxModuleMetaList;
import com.qualcomm.robotcore.hardware.usb.RobotArmingStateNotifier;
import com.qualcomm.robotcore.hardware.usb.RobotUsbDevice;
import com.qualcomm.robotcore.hardware.usb.RobotUsbModule;
import com.qualcomm.robotcore.util.SerialNumber;
import dev.ftcsim.contracts.hardware.HubWire;
import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.firstinspires.ftc.robotcore.external.Consumer;
import org.firstinspires.ftc.robotcore.internal.hardware.TimeWindow;
import org.firstinspires.ftc.robotcore.internal.network.RobotCoreCommandList;
import org.firstinspires.ftc.robotcore.internal.ui.ProgressParameters;

/**
 * The USB device the SDK reaches its REV hubs through, replaced by a direct connection to simulated hubs. The
 * SDK's {@link LynxModule} and everything built on it run unchanged: they serialise commands into datagrams,
 * which go to the simulated bus, and the bus's replies are handed back to the module exactly as the SDK's own
 * incoming-datagram thread would.
 *
 * <p>Commands go out on the calling thread. The SDK's incoming-datagram poller is deliberately absent: it would make
 * the order of events depend on thread scheduling, and the simulated hubs have no use for it. Reply latency is
 * charged to the robot's simulated clock instead, so a command takes the time a command takes. A thread the
 * simulation does not schedule, such as a library's own timer or the SDK's keep-alive, reaches the hubs through the
 * wire's unscheduled path, which answers from the state the hubs are in and never moves or waits on simulated time.
 */
final class SimLynxUsbDevice implements LynxUsbDevice {
    private final SerialNumber serialNumber;
    private final HubWire wire;
    private final Map<Integer, LynxModule> knownModules = new TreeMap<>();
    private final Map<Integer, String> missingModules = new TreeMap<>();
    private final List<RobotArmingStateNotifier.Callback> callbacks = new CopyOnWriteArrayList<>();
    private volatile RobotArmingStateNotifier.ARMINGSTATE armingState = RobotArmingStateNotifier.ARMINGSTATE.ARMED;
    private volatile RobotUsbModule owner;
    private volatile boolean systemSynthetic;
    private volatile boolean engaged = true;
    private volatile boolean throwOnLockAcquisition;

    SimLynxUsbDevice(SerialNumber serialNumber, HubWire wire) {
        this.serialNumber = serialNumber;
        this.wire = wire;
    }

    // ---------------------------------------------------------------------------------------------
    // The transport
    // ---------------------------------------------------------------------------------------------

    @Override
    public void transmit(LynxMessage message) {
        LynxDatagram datagram = message.getSerialization();
        if (datagram == null) {
            // The module already finished with this message, for instance because a reply to an earlier send arrived.
            return;
        }
        long sent = System.nanoTime();
        message.setNanotimeLastTransmit(sent);
        byte[] bytes = datagram.toByteArray();
        // A thread the simulation does not schedule, such as a library's timer, must neither move nor wait on simulated time.
        boolean scheduled = Hooks.isScheduledThread();
        HubWire.Exchange exchange = scheduled ? wire.transmit(bytes) : wire.transmitUnscheduled(bytes);
        if (scheduled) {
            Hooks.elapse(exchange.latencyNanos());
        }
        long received = System.nanoTime();
        for (byte[] reply : exchange.replies()) {
            deliver(reply, sent, received);
        }
        message.noteHasBeenTransmitted();
    }

    private void deliver(byte[] reply, long sentNanos, long receivedNanos) {
        LynxDatagram datagram = new LynxDatagram();
        try {
            datagram.fromByteArray(reply);
        } catch (RobotCoreException malformed) {
            throw new IllegalStateException("A simulated hub sent a datagram the SDK cannot parse", malformed);
        }
        if (!datagram.isChecksumValid()) {
            throw new IllegalStateException("A simulated hub sent a datagram with a bad checksum: " + datagram.getPacketId());
        }
        TimeWindow window = new TimeWindow();
        window.setNanosecondsFirst(sentNanos);
        window.setNanosecondsLast(receivedNanos);
        datagram.setPayloadTimeWindow(window);
        LynxModule module = findKnownModule(datagram.getSourceModuleAddress());
        if (module != null) {
            module.onIncomingDatagramReceived(datagram);
        }
    }

    /**
     * The SDK's way of getting an OpMode that ignores Stop out of its loop when the loop talks to the hubs: for a
     * while every attempt to take the bus lock throws {@code ForceStopException}, except for the keep-alive. The
     * robot's own thread is the runaway, so a library's timer on a thread the simulation does not schedule is spared.
     */
    @Override
    public void acquireNetworkTransmissionLock(LynxMessage message) {
        if (throwOnLockAcquisition && !(message instanceof LynxKeepAliveCommand) && Hooks.isScheduledThread()) {
            throw new OpModeManagerImpl.ForceStopException();
        }
    }

    @Override
    public void releaseNetworkTransmissionLock(LynxMessage message) {
    }

    @Override
    public void lockNetworkLockAcquisitions() {
    }

    @Override
    public void setThrowOnNetworkLockAcquisition(boolean throwOnAcquisition) {
        throwOnLockAcquisition = throwOnAcquisition;
    }

    // ---------------------------------------------------------------------------------------------
    // Modules
    // ---------------------------------------------------------------------------------------------

    @Override
    public LynxModule getOrAddModule(LynxModuleDescription description) throws RobotCoreException, InterruptedException {
        LynxModule module;
        synchronized (knownModules) {
            module = knownModules.get(description.address);
            if (module != null) {
                if (description.isUserModule && !module.isUserModule()) {
                    module.setUserModule(true);
                }
                return module;
            }
            module = new SimLynxModule(this, description.address, description.isParent, description.isUserModule);
            if (description.isSystemSynthetic) {
                module.setSystemSynthetic(true);
            }
            knownModules.put(description.address, module);
        }
        try {
            module.pingAndQueryKnownInterfacesAndEtc();
        } catch (RobotCoreException | InterruptedException | RuntimeException failure) {
            module.close();
            synchronized (knownModules) {
                knownModules.remove(description.address);
            }
            throw failure;
        }
        return module;
    }

    @Override
    public void removeConfiguredModule(LynxModule module) {
        synchronized (knownModules) {
            knownModules.remove(module.getModuleAddress());
        }
    }

    @Override
    public void changeModuleAddress(LynxModule module, int oldAddress, Runnable changeAddress) {
        synchronized (knownModules) {
            knownModules.remove(oldAddress);
            changeAddress.run();
            knownModules.put(module.getModuleAddress(), module);
        }
    }

    @Override
    public void noteMissingModule(int moduleAddress, String moduleName) {
        synchronized (knownModules) {
            missingModules.put(moduleAddress, moduleName);
        }
    }

    @Override
    public LynxModuleMetaList discoverModules(boolean checkForImus) {
        List<LynxModuleMeta> found = new ArrayList<>();
        for (LynxModule module : knownModulesSnapshot()) {
            found.add(new LynxModuleMeta(module.getModuleAddress(), module.isParent()));
        }
        return new LynxModuleMetaList(serialNumber, found);
    }

    List<LynxModule> knownModulesSnapshot() {
        synchronized (knownModules) {
            return List.copyOf(knownModules.values());
        }
    }

    private LynxModule findKnownModule(int address) {
        synchronized (knownModules) {
            return knownModules.get(address);
        }
    }

    @Override
    public void failSafe() {
        for (LynxModule module : knownModulesSnapshot()) {
            module.attemptFailSafeAndIgnoreErrors();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Arming: the simulated device is always plugged in, so it is simply armed
    // ---------------------------------------------------------------------------------------------

    @Override
    public SerialNumber getSerialNumber() {
        return serialNumber;
    }

    @Override
    public RobotArmingStateNotifier.ARMINGSTATE getArmingState() {
        return armingState;
    }

    @Override
    public void registerCallback(RobotArmingStateNotifier.Callback callback, boolean doInitialCallback) {
        callbacks.add(callback);
        if (doInitialCallback) {
            callback.onModuleStateChange(this, armingState);
        }
    }

    @Override
    public void unregisterCallback(RobotArmingStateNotifier.Callback callback) {
        callbacks.remove(callback);
    }

    @Override
    public void arm() {
        changeArmingState(RobotArmingStateNotifier.ARMINGSTATE.ARMED);
    }

    @Override
    public void pretend() {
        changeArmingState(RobotArmingStateNotifier.ARMINGSTATE.PRETENDING);
    }

    @Override
    public void armOrPretend() {
        arm();
    }

    @Override
    public void disarm() {
        changeArmingState(RobotArmingStateNotifier.ARMINGSTATE.DISARMED);
    }

    @Override
    public void close() {
        changeArmingState(RobotArmingStateNotifier.ARMINGSTATE.CLOSED);
    }

    private void changeArmingState(RobotArmingStateNotifier.ARMINGSTATE state) {
        if (armingState != state) {
            armingState = state;
            for (RobotArmingStateNotifier.Callback callback : callbacks) {
                callback.onModuleStateChange(this, state);
            }
        }
    }

    @Override
    public void engage() {
        engaged = true;
    }

    @Override
    public void disengage() {
        engaged = false;
    }

    @Override
    public boolean isEngaged() {
        return engaged;
    }

    @Override
    public SyncdDevice.ShutdownReason getShutdownReason() {
        return SyncdDevice.ShutdownReason.NORMAL;
    }

    @Override
    public void setOwner(RobotUsbModule owner) {
        this.owner = owner;
    }

    @Override
    public RobotUsbModule getOwner() {
        return owner;
    }

    @Override
    public boolean isSystemSynthetic() {
        return systemSynthetic;
    }

    @Override
    public void setSystemSynthetic(boolean systemSynthetic) {
        this.systemSynthetic = systemSynthetic;
    }

    // ---------------------------------------------------------------------------------------------
    // Identity and warnings
    // ---------------------------------------------------------------------------------------------

    @Override
    public HardwareDevice.Manufacturer getManufacturer() {
        return HardwareDevice.Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return "Simulated REV hub USB device";
    }

    @Override
    public String getConnectionInfo() {
        return "simulated " + serialNumber;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
    }

    @Override
    public String getGlobalWarning() {
        return "";
    }

    @Override
    public boolean shouldTriggerWarningSound() {
        return false;
    }

    @Override
    public void suppressGlobalWarning(boolean suppress) {
    }

    @Override
    public void setGlobalWarning(String warning) {
    }

    @Override
    public void clearGlobalWarning() {
    }

    // ---------------------------------------------------------------------------------------------
    // What the simulator does not model: firmware updates, address and system operations, USB internals
    // ---------------------------------------------------------------------------------------------

    @Override
    public RobotUsbDevice getRobotUsbDevice() {
        throw unsupported("getRobotUsbDevice", "there is no USB connection under the simulated hubs");
    }

    @Override
    public LynxUsbDeviceImpl getDelegationTarget() {
        throw unsupported("getDelegationTarget", "the simulated USB device is not a LynxUsbDeviceImpl");
    }

    @Override
    public boolean setupControlHubEmbeddedModule() {
        throw unsupported("setupControlHubEmbeddedModule", "the simulator creates its embedded hub itself");
    }

    @Override
    public void performSystemOperationOnParentModule(
            int parentAddress, Consumer<LynxModule> operation, int timeout, TimeUnit timeoutUnit) {
        throw unsupported("performSystemOperationOnParentModule", "firmware and address maintenance is not simulated");
    }

    @Override
    public void performSystemOperationOnConnectedModule(
            int moduleAddress, int parentAddress, Consumer<LynxModule> operation, int timeout, TimeUnit timeoutUnit) {
        throw unsupported("performSystemOperationOnConnectedModule", "firmware and address maintenance is not simulated");
    }

    @Override
    public LynxUsbDevice.SystemOperationHandle keepConnectedModuleAliveForSystemOperations(int moduleAddress, int parentAddress) {
        throw unsupported("keepConnectedModuleAliveForSystemOperations", "firmware and address maintenance is not simulated");
    }

    @Override
    public RobotCoreCommandList.LynxFirmwareUpdateResp updateFirmware(
            RobotCoreCommandList.FWImage image, String requestId, Consumer<ProgressParameters> progressConsumer) {
        throw unsupported("updateFirmware", "hub firmware cannot be updated in the simulator");
    }

    private static UnsupportedOperationException unsupported(String member, String reason) {
        return new UnsupportedOperationException("SimLynxUsbDevice." + member + ": " + reason);
    }
}
