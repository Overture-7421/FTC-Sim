package dev.ftcsim.sdk.hardware;

import android.content.Context;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import java.util.List;
import java.util.StringJoiner;

/**
 * The SDK's {@link HardwareMap} that creates a device the first time robot code asks for it, wherever the
 * robot code asks: by type and name, through the legacy per-type mappings such as {@code hardwareMap.dcMotor},
 * or as one of several devices of a type. A device is created once and every later lookup finds it through
 * the SDK's own code, so identity, aliasing and enumeration behave as they do on a configured robot.
 */
final class SimHardwareMap extends HardwareMap {
    private final SimHubs hubs;
    private final List<DeviceProvider> providers;

    SimHardwareMap(Context context, OpModeManagerNotifier notifier, SimHubs hubs, List<DeviceProvider> providers) {
        super(context, notifier);
        this.hubs = hubs;
        this.providers = List.copyOf(providers);
        dcMotor = lazy(dcMotor);
        servo = lazy(servo);
        crservo = lazy(crservo);
        analogInput = lazy(analogInput);
        digitalChannel = lazy(digitalChannel);
        touchSensor = lazy(touchSensor);
        colorSensor = lazy(colorSensor);
        led = lazy(led);
    }

    @Override
    public <T> T get(Class<? extends T> classOrInterface, String deviceName) {
        synchronized (lock) {
            String name = deviceName.trim();
            T device = tryGet(classOrInterface, name);
            if (device == null) {
                if (providerFor(classOrInterface) == null && !allDevicesMap.containsKey(name)) {
                    hubs.directory().unsupported(name, classOrInterface.getSimpleName());
                }
                throw new IllegalArgumentException(describeMiss(classOrInterface, name));
            }
            return device;
        }
    }

    @Override
    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        synchronized (lock) {
            String name = deviceName.trim();
            T existing = super.tryGet(classOrInterface, name);
            if (existing != null || (allDevicesMap.containsKey(name) && !isAnotherDriverOfTheSameHardware(classOrInterface, name))) {
                return existing;
            }
            return provision(classOrInterface, name) == null ? null : super.tryGet(classOrInterface, name);
        }
    }

    /** Whether what the name holds is hardware that {@code requested} is another driver of, so that it can be added beside it. */
    private boolean isAnotherDriverOfTheSameHardware(Class<?> requested, String name) {
        DeviceProvider provider = providerFor(requested);
        return provider != null && provider.drivesSameHardwareAs(requested, allDevicesMap.get(name));
    }

    /** Creates and registers the device if some provider makes devices of that type; returns null otherwise. */
    private HardwareDevice provision(Class<?> requested, String name) {
        DeviceProvider provider = providerFor(requested);
        if (provider == null) {
            return null;
        }
        HardwareDevice device = provider.create(hubs, requested, name);
        put(name, device);
        for (DeviceMapping<? extends HardwareDevice> mapping : allDeviceMappings) {
            addIfFits(mapping, name, device);
        }
        return device;
    }

    private static <T extends HardwareDevice> void addIfFits(DeviceMapping<T> mapping, String name, HardwareDevice device) {
        if (mapping.getDeviceTypeClass().isInstance(device) && !mapping.contains(name)) {
            mapping.putLocal(name, mapping.cast(device));
        }
    }

    private DeviceProvider providerFor(Class<?> requested) {
        for (DeviceProvider provider : providers) {
            if (provider.provides(requested)) {
                return provider;
            }
        }
        return null;
    }

    private String describeMiss(Class<?> requested, String name) {
        String sdkText = "Unable to find a hardware device with name \"" + name + "\" and type " + requested.getSimpleName();
        List<HardwareDevice> existing = allDevicesMap.get(name);
        if (existing != null) {
            StringJoiner kinds = new StringJoiner(", ");
            for (HardwareDevice device : existing) {
                kinds.add(device.getClass().getSimpleName());
            }
            return sdkText + ": \"" + name + "\" already exists as " + kinds + " and cannot also be that type";
        }
        StringJoiner families = new StringJoiner("; ");
        for (DeviceProvider provider : providers) {
            families.add(provider.family());
        }
        return sdkText + ". The simulator creates devices on demand for: " + families + ". " + requested.getName()
                + " is not one of them.";
    }

    private <T extends HardwareDevice> DeviceMapping<T> lazy(DeviceMapping<T> original) {
        DeviceMapping<T> replacement = new LazyMapping<>(original.getDeviceTypeClass());
        allDeviceMappings.set(allDeviceMappings.indexOf(original), replacement);
        return replacement;
    }

    /** A per-type mapping that creates the device it is asked for instead of reporting it missing. */
    private final class LazyMapping<T extends HardwareDevice> extends DeviceMapping<T> {
        LazyMapping(Class<T> deviceTypeClass) {
            super(deviceTypeClass);
        }

        @Override
        public T get(String deviceName) {
            synchronized (lock) {
                String name = deviceName.trim();
                if (!contains(name) && !allDevicesMap.containsKey(name)) {
                    provision(getDeviceTypeClass(), name);
                }
                return super.get(name);
            }
        }
    }
}
