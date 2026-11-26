package dev.ftcsim.sdk;

import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import java.nio.file.Path;

/** The application context the simulated robot controller hands to the SDK. */
final class SimApplication extends Application {
    /** The application id of the FTC Robot Controller app, which the SDK compares with its own to tell robot controller from driver station. */
    private static final String ROBOT_CONTROLLER_PACKAGE = "com.qualcomm.ftcrobotcontroller";

    private final UsbManager usbManager = new UsbManager();
    private final WifiManager wifiManager = new WifiManager();
    private final UiModeManager uiModeManager = new UiModeManager();
    private final String codePath;
    private final PackageManager packageManager = new PackageManager() {
        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
            if (!ROBOT_CONTROLLER_PACKAGE.equals(packageName)) {
                throw new NameNotFoundException(packageName);
            }
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = packageName;
            info.sourceDir = codePath;
            return info;
        }

        @Override
        public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
            if (!ROBOT_CONTROLLER_PACKAGE.equals(packageName)) {
                throw new NameNotFoundException(packageName);
            }
            PackageInfo info = new PackageInfo();
            info.packageName = packageName;
            return info;
        }
    };

    /** @param applicationPackage the APK that lists the classes of the robot program, see {@link ApplicationPackage} */
    SimApplication(Path applicationPackage) {
        codePath = applicationPackage.toString();
    }

    @Override
    public Object getSystemService(String name) {
        return switch (name) {
            case Context.USB_SERVICE -> usbManager;
            case Context.WIFI_SERVICE -> wifiManager;
            case Context.UI_MODE_SERVICE -> uiModeManager;
            default -> super.getSystemService(name);
        };
    }

    @Override
    public String getPackageName() {
        return ROBOT_CONTROLLER_PACKAGE;
    }

    /** The application package, which the SDK and robot libraries open to list the classes of the app. */
    @Override
    public String getPackageCodePath() {
        return codePath;
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }
}
