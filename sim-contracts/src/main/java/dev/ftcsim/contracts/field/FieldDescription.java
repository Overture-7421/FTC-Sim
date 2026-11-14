package dev.ftcsim.contracts.field;

import java.util.List;

/**
 * Everything the presentation and the sensors need to know about a field without touching its physics: the fixed
 * scenery, how each kind of moving body looks, the tags, and the places a robot can start. The field frame is the
 * FTC field coordinate system of the game's SDK metadata: origin at the centre of the field, Z up, metres.
 *
 * @param id          short stable name, such as {@code decode} or {@code biobuzz}
 * @param humanActions what the people at the field can do, if the game has any
 */
public record FieldDescription(
        String id,
        String displayName,
        List<Scenery> scenery,
        List<BodyKind> bodyKinds,
        List<FieldTag> tags,
        List<StartPose> startPoses,
        List<HumanAction> humanActions) {

    public FieldDescription {
        scenery = List.copyOf(scenery);
        bodyKinds = List.copyOf(bodyKinds);
        tags = List.copyOf(tags);
        startPoses = List.copyOf(startPoses);
        humanActions = List.copyOf(humanActions);
    }
}
