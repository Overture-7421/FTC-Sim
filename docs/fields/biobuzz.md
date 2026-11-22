# BIOBUZZ presented by RTX (2026-27) field

Sources, dimensions, rules and estimates of the simulated BIOBUZZ field (`dev.ftcsim.game.biobuzz` in `games`); read it to know what the field does and what is only a guess.

`BiobuzzField` builds the whole field in an empty `PhysicsWorld` and `BiobuzzRun` plays its rules. Numbers are metres in the
field frame unless they say inches (the CAD is in inches; `Solids.in` converts).

## Frame

Origin at the middle of the field on the tile top, Z up, +X towards the audience, +Y towards the blue wall (red wall at -Y,
the audience's left). The field is the same turned half a turn about the origin, which swaps red and blue. The SDK gives only
placeholder poses for the BIOBUZZ tags, so there is no global tag map: every tag is fixed to a HIVE (`FieldTag.hostEntityId`)
and the vision model works from that body's live pose.

## Sources

| What | Source |
| --- | --- |
| Rules, scoring, setup, G304, G426, G427 | BIOBUZZ Competition Manual TU09: 9.6 HIVE, 9.7 FLOWER, 9.8 SCORING ELEMENTS, 10.3 setup, 10.5 scoring |
| HIVE acceptance tests | Field acceptance checklist 2.3 to 2.7; event field setup guide, calibration table (12.3) |
| Tape, zones, setup positions | Event field setup guide, field mitigation guide, initial field guide |
| Geometry | Official STEP CAD (`field-cad-step.zip`), read with a script: perimeter, frame, HIVE, FLOWER, tape, balls, tags |
| Tags | FTC SDK 12.0.0 `AprilTagGameDatabase`, BIOBUZZ library: tags 30 to 45, 3.25 in, in clusters of four |
| Weights | AndyMark product pages: POLLEN am-5851 0.055 lb, NECTAR am-5852 0.091 lb; the manual gives none |
| Field coordinates | FTC field coordinate system documentation: origin at the middle, Z up, red wall on the audience's left |

## Dimensions

| Item | Value | Where from |
| --- | --- | --- |
| Inside of the perimeter | 144 by 144 in between inner wall faces at +-72 in. Wall boxes are 1.13 in thick and 11.83 in high | Nominal playing area with simplified wall envelopes |
| Floor | one flat slab, top at Z = 0; tile seams and under-tile strips not modelled | CAD |
| FLOWER axes (in) | (-23.39, 69.52), (-69.52, -23.39), (69.52, 23.39), (23.39, -69.52), each in front of the nearest wall | CAD, shifted with the perimeter |
| FLOWER rings | lower ring lip 0.35 in high round a pocket of 2.79 in; middle ring seat 3.86 to 4.12 in, collar to 5.25 in; top ring 20.25 to 21.40 in, inner radius 2.0 in | CAD, CM 9.7 |
| FLOWER pipes, posts | four pipes of radius 0.525 in at (+-1.725, +-1.725) in, 4.25 to 21.25 in; two posts; V-shaped backstop bar above the top ring; wall bracket ring | CAD |
| FLOWER scoring volume | between the middle and top ring, 3.86 to 20.25 in, 2.44 in round the axis, "at least partially within" | CM 10.5.2, CAD ref. 10-4 |
| Frame | two A-frames 38.94 in deep, joined by a bar, a panel on each side; pivot axes 43.95 in above the tiles, 12.74 in either side of the centre line | CM 9.6.1, CAD |
| HIVE | arm turning about an axis along Y, +-30 degrees; house-shaped CELL interior 12.02 in deep (9.44 to 21.46 in from the pivot), 13.96 in high at the ridge, 7.61 in at the eave, 20 in wide | CM 9.6.2, figures 9-7 and 9-11, CAD |
| Tags | 3.25 in; four per CELL at 2.75 and 6.5 in either side of the middle, on the outside of the CELL floor, 7.19 in behind the CELL front | SDK 12, CM figure 9-15 |
| LOADING ZONES | red x -46.60 to -23.91 in, y from the wall to -60.58 in; blue the mirror image | CAD tape, shifted with the perimeter |
| GARDENS | red x 69.58 to 71.58 in, y -71.58 to -48.89 in (against the +X wall); blue the mirror image | CAD tape, shifted with the perimeter |
| POLLEN | ball of radius 1.40 in (2.80 in), 0.02495 kg | CAD, am-5851 |
| NECTAR | ball of radius 1.81 in (3.62 in), 0.04128 kg | CAD, am-5852 |

The [FIRST field STEP assembly](https://ftc-resources.firstinspires.org/ftc/field/field-cad-step) (release 26-27.2) has a slightly smaller interior than the nominal field. Measuring the transformed solids in inches, the opposing inner rail faces are 141.17 in apart and the glass faces are 141.35 in apart. The simulator uses the requested 144 in clear span. The FLOWERS, tape and wall-side staging move with their walls. The centre frame and HIVES keep their field coordinates.

In CAD the FLOWER axes lie 23.39 in along the wall and 68.04 in from field centre. The four starting POLLEN centres are 1.40, 4.29, 7.18 and 10.07 in above the tiles. The loading tape is 11 in deep and the garden strip is 2 by 22.69 in. The frame foot spans 38.94 in, and the HIVE bearing axes are at z = 43.95 in and y = -12.742 and +12.758 in (simulated symmetrically). With the HIVE rotated back to level, the CELL extends from 9.44 to 21.46 in along its arm and the 17 by 5 in tag backing spans 11.52 to 16.52 in. Individual printed tag identities and positions use the SDK and manual. Start presets and masses are simulation inputs.

The CAD puts the underside of the middle ring at 3.90 in (a retrieval opening of 3.55 in, as the manual says). The model uses
3.86 in so that a 3.62 in NECTAR cannot squeeze through within the engine's 1 mm contact tolerance.

## What is physics and what is a rule

- **Solids** are boxes, tilted boxes and 16-sided rings of boxes; the same list is the scenery of the description, so what is
  drawn is what is collided with. Tape is drawn only; frame feet, screws and wall brackets are left out.
- **HIVE.** A dynamic body on a hinge (`PhysicsWorld.addPivot`) that the load turns: two CELLS of thickened boxes round the CAD
  interior, mass 1.5 kg, centre of mass 58.5 mm above the pivot with the arm level, stops at +-30 degrees, a velocity damper
  over the last 6 degrees before each stop. Pieces in a CELL are ordinary bodies, so a load tips the HIVE by its weight and
  position; the CELL that goes down spills through its opening onto the floor and the other end rises with its tags. A TIP is
  counted once, when the arm enters the damper zone on the other side (the manual's "damper begins to contact the frame"); the
  ordered list is `BiobuzzRun.tips()`.
  Each CELL is house-shaped, as in figure 9-7 of the manual: upright sides up to an eave 7.61 in above the floor (figure 9-11), then
  two roof slopes rising to the 12.50 in ridge, with a gabled back wall (the old model was a rectangular box). The eave The skins are translucent (back and gable, floor, sides, both roof slopes); only the
  entry is open. The gable skin is one triangular prism (`Shape.Wedge`); the roof is solid for collisions and the collision gable wall is stepped, as colliders are boxes; a piece inside the CELL counts only while
  it is under the roof line. The coloured ribs outline the house outside the skins rather than sharing their exposed planes,
  and the white tag backings sit behind the tag planes, avoiding Z-fighting. The tag poses and the load calibration are unchanged.
- **Where a ball can go in.** A CELL is open only at the far end of its arm, so one that faces up opens outward and 30 degrees up, its mouth about 0.47 m out from the pivot and 1.4 m up (red audience CELL at the start). A ball goes in only on a steep lob (55 to 85 degrees) from that side; from the other side the HIVE is in the way. Lobbed from a robot's exit height (about 0.4 m) at about 6 m/s, three POLLEN tip the HIVE from places on that side from x 0.4 m (red, audience side) out to the field's end (`HiveTest`, [biobuzz-stub](../robots/biobuzz-stub.md)). The CELL that faces down takes nothing.
- **Calibration.** The checklist wants 7 POLLEN not to tip and 8 to tip; 3 NECTAR with 2 POLLEN not to tip and 3 to tip. Both
  hold if a NECTAR weighs about 5/3 of a POLLEN (AndyMark weights: 1.65). The centre of mass height was swept until all eight
  loads of `HiveTest` came out as the checklist says, set down gently or tossed in at 1.5 m/s, for both HIVES. The window is
  narrow, 57.5 to 59.5 mm (+-1.7 %): at the start position seven POLLEN turn the arm with about 0.41 N m (must not tip), eight
  with about 0.46 N m (must).
- **FLOWER.** Real collision rings and pipes. A POLLEN dropped in from the top falls to the pocket; a NECTAR rests on the
  middle ring seat, whose opening is narrower than a NECTAR. So the stack is ordered by loading order, and a POLLEN in the
  pocket can be knocked out through the retrieval opening while a NECTAR cannot. When the bottom POLLEN is removed or rolls
  clear, the remaining POLLEN in that flower wake and fall. A piece perched off-centre on the middle seat is guided toward its bore.
  A short outward and upward impulse at the lip approximates the colibri plate guiding each piece through the opening.
  Intake capture still depends on roller operation and available space.
- **Pieces** are balls, staged as the setup guide says: four POLLEN stacked in each FLOWER, four along the wall from the corner
  of each GARDEN, three NECTAR of its colour in the CELL facing up of each HIVE (red audience CELL, blue scoring CELL), the
  other five of each colour with that alliance's human player. Every robot starts with four preload POLLEN: the robot's own come
  from `takeFromReserve(POLLEN)` (not in the world), its partner's lie in the middle of its LOADING ZONE against the wall, the
  other alliance's eight in the middle of theirs (CM 10.3.1: robots not present). At the start: 44 bodies (42 pieces, two
  HIVES) and 14 pieces in reserve, 56 in all.
- **Rolling.** `PieceSpec.rollingResistance` (0.06) is a constant slowdown of 0.42 m/s^2 on any static surface, so a ball
  rolled at 1 m/s stops after about a metre (without it the engine leaves a ball crawling at several cm/s for ever), and a
  piece in a CELL or FLOWER stays put unless the surface tilts more than 3.4 degrees.
- **Phases.** AUTO is 30 s from `enterPhase(AUTONOMOUS)`; what happens until `enterPhase(TELEOP)`, the 8 s transition included,
  counts as AUTO (CM 10.5.B). TELEOP ends 120 s after it started and the run moves itself to `ENDED`. `oneMinuteLeft()` is true
  from 60 s before the end and in PRACTICE. After `ENDED` the score latches when all pieces and both HIVES have rested for
  0.25 s, or after 10 s (`isFinal()`).
- **Scoring** (CM 10.5), assessed from the bodies as they are, so a resting or bouncing piece is never counted twice:

  | Item | Points | Read |
  | --- | --- | --- |
  | LEAVE / AUTO PARK | 3 / 5 | when AUTO ends |
  | HIVE TIP | 20 each | AUTO or TELEOP |
  | Piece in the CELL that faces up | 2 each | |
  | FLOWER: alliance whose NECTAR is topmost owns it | 2 per piece in its scoring volume | |
  | FLOWER: alliance whose NECTAR is lowest | 5 | |
  | Piece partly in its GARDEN, any colour | 1 each | |
  | TELEOP PARK (partly in the LOADING ZONE) | 5 | at the end |

  A piece counts in one place only, in the order CELL, FLOWER, GARDEN. In PRACTICE everything is read live. The score is the
  robot's alliance's.
- **Humans and returns.** BIOBUZZ offers no manual field-action buttons; the viewport keeps **Details**. POLLEN that leaves
  the field automatically returns on the same step, just inside the wall or corner where it left, with no outward velocity.
  NECTAR that leaves the field goes back to its human's reserve, not automatically into play. The field-level `load-nectar`
  and `load-opponent-nectar` operations still model entry through the LOADING ZONE: once per TIP of that colour of HIVE
  (G426.A), freely with 60 s or less left (G426.B), always for a NECTAR that left the field; five NECTAR per colour at setup.
  These operations are not exposed by the window. NECTAR is never handed directly to a robot.
- **Seed.** Moves FLOWER stacks by up to 0.06 in and GARDEN and LOADING ZONE balls by up to 0.08 in.
- **Start poses.** Six, as G304 asks: on the robot's own half, touching its own wall, facing the field, outside the LOADING ZONE
  and clear of every FLOWER and piece: red x = 46, 0 and -58 in at 9.2 in from the wall; blue the half turn. LEAVE and PARK use
  the robot's outline, the rectangle of `FieldSetup` (default 18 in).

## Estimated numbers

Not from a source:
- HIVE: mass 1.5 kg, inertia 0.27 kg m^2 about the pivot, centre of mass height 58.5 mm (calibrated), bearing friction
  0.004 N m, damper 2.0 N m s/rad over 6 degrees, CELL friction 0.35 and bounce 0.1.
- HIVE visual skins: 0.03 in thick, 25% opaque. Physics still uses the outward-thickened walls described above.
- Balls: friction 0.5, bounce 0.3, rolling resistance 0.06, spin damping 0.05/s.
- Surfaces: floor friction 0.8, walls 0.4, FLOWER and frame 0.35, no bounce.

The NECTAR to POLLEN weight ratio of 1.65 comes from the AndyMark weights; the manual gives none.

## Known gaps

- No fouls, ranking points or opposing robot. A tip caused by anything but a ball in the CELL (G417, G420) still counts, and
  early FLOWER scoring (G410) is scored.
- The manual does not say whether "POLLEN and/or NECTAR remaining in CELL" is 2 points per piece or per CELL, nor which alliance
  a TIP counts for. Chosen: per piece (the point table's wording and community calculators), and the alliance whose colour the
  HIVE is.
- Early NECTAR entry is refused rather than penalised.
- Frame feet, under-tile strips and the tape of the ALLIANCE AREAS are not modelled; the tape of the zones is drawn but does not
  collide.
- The first step of a new world costs 3 to 6 ms (all contacts at once); later steps 0.03 to 0.06 ms (99th percentile 0.11 to
  0.34 ms of the 4.17 ms budget, measured while other work ran on the machine).
