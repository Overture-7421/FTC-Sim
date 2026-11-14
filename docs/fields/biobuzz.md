# BIOBUZZ presented by RTX (2026-27) field

Package `dev.ftcsim.game.biobuzz` in `game-biobuzz`. `BiobuzzField` builds the whole field in an empty `PhysicsWorld`
and `BiobuzzRun` plays its rules. Numbers are metres in the field frame unless they say inches; the CAD is in inches and
`Solids.in` converts.

## Sources

| What | Source |
| --- | --- |
| Rules, scoring, setup, G304, G426, G427 | BIOBUZZ Competition Manual TU09: 9.6 HIVE, 9.7 FLOWER, 9.8 SCORING ELEMENTS, 10.3 setup, 10.5 scoring |
| HIVE acceptance tests | Field acceptance checklist 2.3 to 2.7; event field setup guide, calibration table (12.3) |
| Tape, zones, setup positions | Event field setup guide, field mitigation guide, initial field guide |
| Geometry | Official STEP CAD (`field-cad-step.zip`), read with a script: perimeter, frame, HIVE, FLOWER, tape, balls, tags |
| Tags | FTC SDK 12.0.0 `AprilTagGameDatabase`, BIOBUZZ library: tags 30 to 45, 3.25 in, in clusters of four |
| Weights | AndyMark product pages: POLLEN am-5851 0.055 lb, NECTAR am-5852 0.091 lb; the manual gives none |
| Field coordinates | FTC field coordinate system documentation: origin at the middle of the field, Z up, the red wall on the audience's left |

## Frame

Origin at the middle of the field on the tile top, Z up, +X towards the audience, +Y towards the blue wall: the red
wall is at -Y (audience's left), the blue wall at +Y. The field is the same when turned half a turn about the origin,
which swaps red and blue. The SDK gives only placeholder poses
for the BIOBUZZ tags, so no global tag map exists: every tag is fixed to a HIVE (`FieldTag.hostEntityId`) and the
vision model works from the live pose of that body.

## Dimensions that were chosen

| Item | Value | Where from |
| --- | --- | --- |
| Inside of the perimeter | +-70.52 in (141.04 in); walls 1.13 in thick, 11.83 in high | CAD; the manual says "approximately 144 in" |
| Floor | one flat slab, top at Z = 0; tile seams and the under-tile strips are not modelled | CAD |
| FLOWER axes (in) | (-23.39, 68.04), (-68.04, -23.39), (68.04, 23.39), (23.39, -68.04), each in front of the nearest wall | CAD |
| FLOWER rings | lower ring lip 0.35 in high round a pocket of 2.79 in; middle ring seat 3.86 to 4.12 in, collar to 5.25 in; top ring 20.25 to 21.40 in, inner radius 2.0 in | CAD, CM 9.7 |
| FLOWER pipes, posts | four pipes of radius 0.525 in at (+-1.725, +-1.725) in, 4.25 to 21.25 in; two posts; V-shaped backstop bar above the top ring; wall bracket ring | CAD |
| FLOWER scoring volume | between the middle and the top ring, 3.86 to 20.25 in, 2.44 in round the axis, "at least partially within" | CM 10.5.2, CAD ref. 10-4 |
| Frame | two A-frames 38.94 in deep, joined by a bar, a panel on each side; pivot axes 43.95 in above the tiles, 12.74 in either side of the centre line | CM 9.6.1, CAD |
| HIVE | arm turning about an axis along Y, +-30 degrees (60 degrees of travel); CELL interior 12.02 in deep (9.44 to 21.46 in from the pivot), 13.96 in high, 20 in wide | CM 9.6.2, CAD |
| Tags | 3.25 in; four per CELL at 2.75 and 6.5 in either side of the middle, on the outside of the CELL floor, 7.19 in behind the front of the CELL | SDK 12, CM figure 9-15 |
| LOADING ZONES | red x -46.60 to -23.91 in, y -wall to -59.10 in; blue the mirror image | CAD tape |
| GARDENS | red x 68.10 to 70.10 in, y -70.10 to -47.41 in (against the +X wall); blue the mirror image | CAD tape |
| POLLEN | ball of radius 1.40 in (2.80 in), 0.02495 kg | CAD, am-5851 |
| NECTAR | ball of radius 1.81 in (3.62 in), 0.04128 kg | CAD, am-5852 |

The CAD puts the underside of the middle ring at 3.90 in (a retrieval opening of 3.55 in, as the manual says). The model
uses 3.86 in so that a 3.62 in NECTAR cannot squeeze through within the engine's 1 mm contact tolerance.

## What is physics and what is a rule

- **Solids** are boxes, tilted boxes and 16-sided rings of boxes; the same list that collides is the scenery of the
  description, so what is drawn is what is collided with. Tape is drawn only. The perimeter is four wall boxes; the
  frame feet, the screws and the brackets under the wall are left out.
- **HIVE.** A dynamic body on a hinge (`PhysicsWorld.addPivot`) that the load turns: two CELLS of thickened boxes round
  the CAD interior, mass 1.5 kg, centre of mass 58.5 mm above the pivot when the arm is level, stops at +-30 degrees, a
  velocity damper over the last 6 degrees before each stop. Pieces in a CELL are ordinary bodies resting on the CELL
  floor, so a load tips the HIVE by its weight and position, the CELL that goes down spills through its opening onto
  the floor, and the other end comes up with its tags. A TIP is counted once, when the arm enters the damper zone on the
  other side (the manual's "damper begins to contact the frame"); it is the ordered `BiobuzzRun.tips()` event list.
- **Calibration.** The checklist wants 7 POLLEN not to tip and 8 to tip, and 3 NECTAR with 2 POLLEN not to tip and 3 to
  tip. Both hold if a NECTAR weighs about 5/3 of a POLLEN: the AndyMark weights give 1.65. The centre of mass height was
  swept until all eight loads in `HiveTest` came out as the checklist says, set down gently or tossed in at 1.5 m/s, for
  both HIVES. The window is narrow: 57.5 to 59.5 mm, which is +-1.7 %, because 7 against 8 POLLEN is a 14 % difference in
  load. At the start position the load of seven POLLEN turns the arm with about 0.41 N m, which must not tip it, and
  that of eight with about 0.46 N m, which must.
- **FLOWER.** Real collision rings and pipes. A POLLEN dropped in from the top falls to the pocket, a NECTAR comes to rest
  on the middle ring seat, whose opening is narrower than a NECTAR, so the stack is ordered by the order of loading and
  a POLLEN in the pocket can be knocked out through the retrieval opening while a NECTAR cannot.
- **Pieces** are balls. They lie where the setup guide stages them: four POLLEN stacked in each FLOWER, four lined along
  the wall from the corner of each GARDEN, three NECTAR of its colour in the CELL facing up of each HIVE (the red
  audience CELL, the blue scoring CELL), the other five NECTAR of each colour with that alliance's human player. Every
  robot starts with four preload POLLEN: the robot's own are handed out by `takeFromReserve(POLLEN)` and are not in the
  world, its partner's lie in the middle of its LOADING ZONE against the wall, and the other alliance's eight lie in the
  middle of theirs (CM 10.3.1: robots that are not present). 44 bodies at the start, 42 pieces and two HIVES, and 14
  pieces in reserve: 56 in all.
- **Rolling.** A ball on the tiles gets rolling resistance from the engine layer (`PieceSpec.rollingResistance`, 0.06): a
  constant slowdown of 0.42 m/s^2, so a ball rolled at 1 m/s stops after about a metre and then sleeps. Without it the
  engine leaves a damped ball crawling at several cm/s for ever. It applies to every static surface under a piece, so
  also to a piece in a CELL or stacked in a FLOWER, which stays put unless the surface tilts more than 3.4 degrees.
- **Phases.** AUTO is 30 s from `enterPhase(AUTONOMOUS)`; what happens until `enterPhase(TELEOP)`, the 8 s transition
  included, counts as AUTO (CM 10.5.B). TELEOP ends 120 s after it started and the run moves itself to `ENDED`.
  `oneMinuteLeft()` is true from 60 s before the end and in PRACTICE. After `ENDED` the score latches when all pieces and
  both HIVES have been at rest for 0.25 s, or after 10 s (`isFinal()`).
- **Scoring** (CM 10.5, assessed from the bodies as they are, so a resting or bouncing piece is never counted twice):
  LEAVE 3 and AUTO PARK 5 are read when AUTO ends; HIVE TIP 20 each, in AUTO or TELEOP; 2 for each piece in the CELL that
  faces up; in each FLOWER the alliance whose colour of NECTAR is topmost owns it and gets 2 for every piece in its
  scoring volume, and the alliance whose NECTAR is lowest gets 5; 1 for each piece partly in its GARDEN, of any colour;
  TELEOP PARK 5 (partly in the LOADING ZONE, read at the end). A piece counts in one place only, in the order CELL,
  FLOWER, GARDEN. In PRACTICE everything is read live. The score is the robot's alliance's. Its lines, in order: LEAVE,
  AUTO PARK, HIVE TIP in AUTO, HIVE TIP in TELEOP, POLLEN and NECTAR in CELL, FLOWER bottom NECTAR bonus, POLLEN and
  NECTAR in owned FLOWER, POLLEN and NECTAR in GARDEN, TELEOP PARK.
- **Humans.** `load-nectar` and `load-opponent-nectar` drop one NECTAR into the LOADING ZONE: once for each TIP of that
  colour of HIVE (G426.A), freely when 60 s or less remain (G426.B), always for a NECTAR that left the field; five
  NECTAR per colour. `return-pollen` puts the POLLEN that has been outside longest back in at the wall where it left.
  NECTAR that leaves the field goes back to its human; POLLEN waits for the staff. `takeFromReserve` hands out only the
  robot's four preload POLLEN: NECTAR enters the game only through these actions, never into a robot.
- **Seed.** Moves the FLOWER stacks by up to 0.06 in and the GARDEN and LOADING ZONE balls by up to 0.08 in.
- **Start poses.** Six, as G304 asks: on the robot's own half, touching its own wall, facing the field, outside the
  LOADING ZONE and clear of every FLOWER and every piece: red x = 46, 0 and -58 in at 9.2 in from the wall; blue the
  half turn of those.
- **Robot outline.** LEAVE and PARK use the rectangle of `FieldSetup` (default 18 in).

## Estimated numbers

Not from a source: the HIVE mass 1.5 kg, inertia 0.27 kg m^2 about the pivot, centre of mass height 58.5 mm (calibrated),
bearing friction 0.004 N m, damper 2.0 N m s/rad and the 6 degree damper zone, CELL friction 0.35 and bounce 0.1; ball
friction 0.5, bounce 0.3, rolling resistance 0.06 and spin damping 0.05/s; floor friction 0.8, walls
0.4, FLOWER and frame 0.35, no bounce; the nectar to pollen weight ratio of 1.65 is the AndyMark weights, not a constant
the manual gives.

## Known gaps

- No fouls, no ranking points, no opposing robot. A tip caused by anything but a ball in the CELL (G417, G420) still
  counts, and early FLOWER scoring (G410) is scored.
- The manual does not say whether "POLLEN and/or NECTAR remaining in CELL" is 2 points per piece or per CELL, nor which
  alliance a TIP counts for. Per piece (the point table's wording and the community calculators), and the alliance whose
  colour the HIVE is.
- Early NECTAR entry is refused rather than penalised.
- The frame's feet, the under-tile strips and the tape of the ALLIANCE AREAS are not modelled; the tape of the zones is
  drawn but does not collide.
- The first step of a new world costs 3 to 6 ms, for all the contacts of the field at once; later steps cost 0.03 to 0.06 ms.

## Checked by tests

`HiveTest`: the checklist's loads and the setup guide's, on both HIVES, a spill, ordered tips, the other alliance's
HIVE. `FlowerTest`: stacking order, NECTAR resting in the ring, retrieval of POLLEN but not NECTAR. `RulesTest`: FLOWER
ownership and the bottom bonus, GARDEN, LEAVE and PARK, the phases and the settle, human NECTAR, pieces leaving the
field. `FieldTest`: description against the world, tags following their HIVE, start poses, equal seeds, conservation of
56 pieces, a rolling piece stopping, step cost. Step cost with the whole field, a driving robot and a tipping HIVE: mean
0.03 to 0.06 ms, 99th percentile 0.11 to 0.34 ms of the 4.17 ms budget, measured while other work ran on the machine.
