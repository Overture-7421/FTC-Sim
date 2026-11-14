package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.CommandTable.Effect.MUTATING;
import static dev.ftcsim.hardware.lynx.CommandTable.Effect.QUERY;

import java.util.function.Function;

/** The motor commands of the DEKA interface, mapped onto {@link MotorChannel}. */
final class MotorCommands {

    private static final int POWER_LIMIT = 32767;

    private final MotorBank motors;

    MotorCommands(MotorBank motors) {
        this.motors = motors;
    }

    void register(CommandTable table) {
        table.register(DekaCommand.SET_MOTOR_CHANNEL_MODE, MUTATING, params -> {
            MotorChannel motor = channel(params);
            RunMode mode = RunMode.fromWire(params.u8(0, RunMode.values().length - 1));
            boolean floatAtZero = params.flag();
            params.end();
            motor.configure(mode, floatAtZero);
            return Reply.ACK;
        });
        table.register(DekaCommand.GET_MOTOR_CHANNEL_MODE, QUERY, reading(
                motor -> new PayloadWriter().u8(motor.mode().wire()).flag(motor.floatAtZero())));
        table.register(DekaCommand.SET_MOTOR_CHANNEL_ENABLE, MUTATING, params -> {
            MotorChannel motor = channel(params);
            boolean enabled = params.flag();
            params.end();
            motor.enable(enabled);
            return Reply.ACK;
        });
        table.register(
                DekaCommand.GET_MOTOR_CHANNEL_ENABLE, QUERY, reading(motor -> new PayloadWriter().flag(motor.enabled())));
        table.register(DekaCommand.SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL, MUTATING, params -> {
            MotorChannel motor = channel(params);
            int milliamps = params.u16(0, 0xFFFF);
            params.end();
            motor.setCurrentAlertMilliamps(milliamps);
            return Reply.ACK;
        });
        table.register(
                DekaCommand.GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL,
                QUERY,
                reading(motor -> new PayloadWriter().u16(motor.currentAlertMilliamps())));
        table.register(DekaCommand.RESET_MOTOR_ENCODER, MUTATING, params -> {
            MotorChannel motor = channel(params);
            params.end();
            motor.resetEncoder();
            return Reply.ACK;
        });
        table.register(DekaCommand.SET_MOTOR_CONSTANT_POWER, MUTATING, params -> {
            MotorChannel motor = channel(params);
            int power = params.i16(-POWER_LIMIT, POWER_LIMIT);
            params.end();
            motor.setConstantPower(power);
            return Reply.ACK;
        });
        table.register(
                DekaCommand.GET_MOTOR_CONSTANT_POWER,
                QUERY,
                reading(motor -> new PayloadWriter().u16(motor.constantPower())));
        table.register(DekaCommand.SET_MOTOR_TARGET_VELOCITY, MUTATING, params -> {
            MotorChannel motor = channel(params);
            int velocity = params.i16(-POWER_LIMIT, POWER_LIMIT);
            params.end();
            motor.setTargetVelocity(velocity);
            return Reply.ACK;
        });
        table.register(
                DekaCommand.GET_MOTOR_TARGET_VELOCITY,
                QUERY,
                reading(motor -> new PayloadWriter().u16(motor.targetVelocity())));
        table.register(DekaCommand.SET_MOTOR_TARGET_POSITION, MUTATING, params -> {
            MotorChannel motor = channel(params);
            int target = params.i32();
            int tolerance = params.u16(0, 0xFFFF);
            params.end();
            motor.setTargetPosition(target, tolerance);
            return Reply.ACK;
        });
        table.register(
                DekaCommand.GET_MOTOR_TARGET_POSITION,
                QUERY,
                reading(motor -> new PayloadWriter().i32(motor.targetPosition()).u16(motor.tolerance())));
        table.register(DekaCommand.IS_MOTOR_AT_TARGET, QUERY, params -> {
            MotorChannel motor = channel(params);
            params.end();
            if (motor.mode() != RunMode.POSITION) {
                throw new CommandRejected(NackReason.COMMAND_INVALID_FOR_MOTOR_MODE);
            }
            return Reply.response(new PayloadWriter().flag(motor.atTarget()));
        });
        table.register(
                DekaCommand.GET_MOTOR_ENCODER_POSITION,
                QUERY,
                reading(motor -> new PayloadWriter().i32((int) motor.position())));
        registerLoopCoefficients(table);
    }

    private void registerLoopCoefficients(CommandTable table) {
        table.register(DekaCommand.SET_MOTOR_PID_COEFFICIENTS, MUTATING, params -> {
            MotorChannel motor = channel(params);
            RunMode mode = loopMode(params);
            int p = params.i32();
            int i = params.i32();
            int d = params.i32();
            params.end();
            motor.loop(mode).configure(new PidfCoefficients(p, i, d, 0, PidfCoefficients.Algorithm.LEGACY_PID));
            return Reply.ACK;
        });
        table.register(DekaCommand.GET_MOTOR_PID_COEFFICIENTS, QUERY, params -> {
            PidfCoefficients coefficients = loop(params);
            // The legacy form cannot carry a feed-forward term, which the SDK relies on to fall back to the PIDF form.
            if (coefficients.feedForward() != 0) {
                throw new CommandRejected(NackReason.param(2));
            }
            return Reply.response(new PayloadWriter()
                    .i32(coefficients.proportional())
                    .i32(coefficients.integral())
                    .i32(coefficients.derivative()));
        });
        table.register(DekaCommand.SET_MOTOR_PIDF_COEFFICIENTS, MUTATING, params -> {
            MotorChannel motor = channel(params);
            RunMode mode = loopMode(params);
            int p = params.i32();
            int i = params.i32();
            int d = params.i32();
            int f = params.i32();
            PidfCoefficients.Algorithm algorithm =
                    PidfCoefficients.Algorithm.values()[params.u8(0, PidfCoefficients.Algorithm.values().length - 1)];
            params.end();
            motor.loop(mode).configure(new PidfCoefficients(p, i, d, f, algorithm));
            return Reply.ACK;
        });
        table.register(DekaCommand.GET_MOTOR_PIDF_COEFFICIENTS, QUERY, params -> {
            PidfCoefficients coefficients = loop(params);
            return Reply.response(new PayloadWriter()
                    .i32(coefficients.proportional())
                    .i32(coefficients.integral())
                    .i32(coefficients.derivative())
                    .i32(coefficients.feedForward())
                    .u8(coefficients.algorithm().ordinal()));
        });
    }

    private MotorChannel channel(Params params) {
        return motors.channel(params.u8(0, MotorBank.COUNT - 1));
    }

    /** The two modes that have loop coefficients: velocity and position. */
    private static RunMode loopMode(Params params) {
        return RunMode.fromWire(params.u8(RunMode.VELOCITY.wire(), RunMode.POSITION.wire()));
    }

    private PidfCoefficients loop(Params params) {
        MotorChannel motor = channel(params);
        RunMode mode = loopMode(params);
        params.end();
        return motor.loop(mode).coefficients();
    }

    /** A command that takes a channel number and answers with a value read from it. */
    private CommandTable.Handler reading(Function<MotorChannel, PayloadWriter> value) {
        return params -> {
            MotorChannel motor = channel(params);
            params.end();
            return Reply.response(value.apply(motor));
        };
    }
}
