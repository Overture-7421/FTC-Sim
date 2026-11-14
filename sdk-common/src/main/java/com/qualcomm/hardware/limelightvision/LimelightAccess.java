package com.qualcomm.hardware.limelightvision;

import org.json.JSONObject;

/**
 * Reaches the parts of the SDK's Limelight classes that only code in their own package can: the constructors of
 * {@link LLStatus} and {@code CalibrationResult} that parse a camera's JSON, the way {@link LLResult} is stamped with
 * the time it arrived, and the JSON that an {@link LLFieldMap} turns into. This class lives in the SDK's package
 * for that reason alone; it adds nothing to what the SDK does with the JSON it is given, so the simulated camera's
 * answers are parsed by the SDK's own code, as a real camera's are.
 */
public final class LimelightAccess {

    private LimelightAccess() {}

    /**
     * Parses a {@code /results} document with the SDK's own parser and stamps it with the time it arrived, as the
     * SDK's polling thread does by reading the clock when it has the response.
     *
     * @param arrivedMillis the Control Hub's wall clock, in milliseconds since 1970, when the response arrived
     * @return the result, or null if the SDK's parser gives none
     */
    public static LLResult parseResult(JSONObject json, long arrivedMillis) {
        LLResult result = LLResult.parse(json);
        if (result != null) {
            result.setControlHubTimeStamp(arrivedMillis);
        }
        return result;
    }

    /** The status the SDK makes of a {@code /status} document. */
    public static LLStatus status(JSONObject json) {
        return new LLStatus(json);
    }

    /** The calibration the SDK makes of a {@code /cal-*} document; null makes the invalid one the SDK returns on failure. */
    public static LLResultTypes.CalibrationResult calibration(JSONObject json) {
        return new LLResultTypes.CalibrationResult(json);
    }

    /** The body the SDK sends when it uploads the field map. */
    public static String fieldMapJson(LLFieldMap map) {
        return map.toJson().toString();
    }
}
