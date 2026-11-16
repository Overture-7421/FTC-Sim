package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import dev.ftcsim.contracts.hardware.CameraWire;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.firstinspires.ftc.robotcore.internal.usb.EthernetOverUsbSerialNumber;

/**
 * The SDK's own {@link Limelight3A}, as the SDK creates it for a camera on the Control Hub's USB port, but talking to a
 * camera model over the simulated link instead of HTTP. The driver does not know where the camera is wired, so any
 * name works; the robot has one Limelight and the first name that asks for it is its name. A second name gets a camera
 * that is not plugged in, and the host records that as a problem.
 */
final class LimelightProvider implements DeviceProvider {

    /** The address Limelight gives itself on the USB network of a Control Hub. */
    private static final byte[] LIMELIGHT_ADDRESS = {(byte) 172, 29, 0, 1};
    private static final String INTERFACE_NAME = "usb0";

    @Override
    public String family() {
        return "Limelight 3A smart camera (Limelight3A, under any name)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return requested == Limelight3A.class;
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        CameraWire cameras = hubs.cameras();
        boolean plugged = cameras.connect(name);
        try {
            InetAddress address = InetAddress.getByAddress(LIMELIGHT_ADDRESS);
            return new SimLimelight3A(
                    EthernetOverUsbSerialNumber.fromIpAddress(address.getHostAddress(), INTERFACE_NAME), name, address, cameras, plugged);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("A four byte address is always a valid address", impossible);
        }
    }
}
