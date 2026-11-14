package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.MotorPlant;

/** The four motor ports of a hub, run in channel order on every firmware tick. */
final class MotorBank {

    static final int COUNT = 4;

    private final MotorChannel[] channels = new MotorChannel[COUNT];

    MotorBank(int hubAddress, Diagnostics diagnostics) {
        for (int i = 0; i < COUNT; i++) {
            Port<MotorPlant> port = new Port<>("hub " + hubAddress + " motor port " + i);
            channels[i] = new MotorChannel(i, port, diagnostics);
        }
    }

    MotorChannel channel(int index) {
        return channels[index];
    }

    void tick(double batteryVolts, double dtSeconds) {
        for (MotorChannel channel : channels) {
            channel.tick(batteryVolts, dtSeconds);
        }
    }

    void failSafe() {
        for (MotorChannel channel : channels) {
            channel.failSafe();
        }
    }

    int totalCurrentMilliamps() {
        int total = 0;
        for (MotorChannel channel : channels) {
            total += channel.currentMilliamps();
        }
        return total;
    }
}
