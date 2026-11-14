package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose

/**
 * Which of a field's start poses to offer to an alliance. The port gives a start pose a name and numbers and nothing
 * about alliances, but the games name their poses for them ("RED GOAL SIDE", "BLUE wall, left"), so that is the data
 * this goes by: a pose whose name begins with RED or BLUE, in any case, belongs to that alliance, and any other name
 * belongs to both.
 */
object StartPoses {
    private const val NUMBER_TOLERANCE = 1e-6
    private val NOT_LETTERS = Regex("[^A-Za-z]+")

    /** The alliance the name of [pose] says it is for, or null when it names none. */
    fun allianceOf(pose: StartPose): Alliance? =
        when (pose.name().trim().split(NOT_LETTERS, limit = 2).firstOrNull()?.uppercase()) {
            "RED" -> Alliance.RED
            "BLUE" -> Alliance.BLUE
            else -> null
        }

    /**
     * The poses [alliance] may start from, in the field's order. A field whose poses name no alliance offers them all,
     * and so does one whose poses are all for the other alliance, since offering nothing helps no one.
     */
    fun forAlliance(poses: List<StartPose>, alliance: Alliance): List<StartPose> {
        if (poses.none { allianceOf(it) != null }) return poses
        return poses.filter { allianceOf(it).let { owner -> owner == null || owner == alliance } }.ifEmpty { poses }
    }

    /** The pose that the numbers stand for, if any: metres and radians, equal to the pose's own. */
    fun matching(poses: List<StartPose>, x: Double, y: Double, headingRadians: Double): StartPose? =
        poses.firstOrNull {
            Math.abs(it.x() - x) < NUMBER_TOLERANCE && Math.abs(it.y() - y) < NUMBER_TOLERANCE &&
                Math.abs(Math.IEEEremainder(it.headingRadians() - headingRadians, 2 * Math.PI)) < NUMBER_TOLERANCE
        }
}
