package dev.ftcsim.sdk.hardware;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.configuration.ConfigurationTypeManager;
import com.qualcomm.robotcore.hardware.configuration.UnspecifiedMotor;

/**
 * The SDK learns which motor and servo types exist by scanning the annotated classes of the app. Its own hub
 * code asks for the unspecified motor type and the standard servo type whenever it sets a controller up, so the
 * types those two come from are registered before any hardware is created. Types a robot program defines for
 * itself are not needed to run it, and are found by the same scan the SDK would do if the program asks.
 */
final class ConfigurationTypes {
    private ConfigurationTypes() {
    }

    static void registerBuiltIns() {
        ConfigurationTypeManager manager = ConfigurationTypeManager.getInstance();
        manager.filterAllClassesStart();
        manager.filterClass(UnspecifiedMotor.class);
        manager.filterClass(Servo.class);
        manager.filterClass(CRServo.class);
        manager.filterAllClassesComplete();
    }
}
