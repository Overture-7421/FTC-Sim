# DECODE (2025-26) field

Package `dev.ftcsim.game.decode` in `games`. `DecodeField` builds the whole field in an empty `PhysicsWorld` and
`DecodeRun` plays its rules. Every number below is metres in the field frame (origin at the middle of the field, Z up,
+X towards the audience, the goals at -X) unless it says otherwise.

## Sources

| What | Source |
| --- | --- |
| Rules, scoring, setup, zones | DECODE Competition Manual TU32: 9.2 FIELD, 9.8 CLASSIFIER, 10.3 setup, 10.5 scoring, G304, G432, G434 |
| Tape and zone placement, tile grid, controlled dimensions | Event field setup guide, field acceptance checklist, field mitigation guide, initial field guide |
| Geometry | AndyMark am-5700_Full STEP (Onshape export of 2025-09-05), read with a small script: panels, ramp rails, gate arm, tape, ARTIFACT positions |
| Tags | FTC SDK 11.1.0 `AprilTagGameDatabase`, DECODE library: tags 20 and 24 (6.5 in); the obelisk tags 21 to 23 have no pose there |
| ARTIFACT weight | AndyMark am-3376a product page: 5 in, 0.165 lb; the manual gives no weight |

## Frame and sides

The SDK's pose of tag 20 puts the blue goal in the -X, -Y corner and the red goal (tag 24) in the -X, +Y corner. Each
alliance's goal is on the opposite side of the field from its alliance area: blue has its goal, CLASSIFIER, gate and
depot at -Y and its loading zone, base zone and secret tunnel at +Y. Red is the mirror image across y = 0. Everything
is written once for blue and mirrored for red.

## Dimensions that were chosen

| Item | Value | Where from |
| --- | --- | --- |
| Inside of the perimeter | +-1.7945 (141.3 in), walls 0.2785 high (11 in glass) | CAD, AndyMark perimeter am-0481 |
| Goal panels | 27 in (0.6858) wide, 0.9842 high; backboards to 1.3652 | CM figure 9-2, CAD |
| Front (tag) panel | normal at 54.05 degrees, centre (-1.5016, -1.4059) | CAD; the SDK's tag 20 lies 5 mm off its outside face |
| Archway | plate in the plane x = -1.23, 0.781 above the floor (CAD part am-5730, x -1.235 to -1.225, y -1.7829 to -1.6114) | CAD |
| Goal internal ramp | a 1 cm polypropylene plate, right triangle (CAD am-5721): legs along the end wall and along y = -1.6162, hypotenuse on the inside of the front panel; top face z = 1.2136 + 0.268 y, tilted 15 degrees about X so it falls towards the side wall; low edge 0.7805 above the floor | CAD |
| Lane | two 1 in square tubes turned 45 degrees (ridge up) 4.5 in apart (y -1.6368 and -1.7511), 15 degrees down along +X from the end wall, axis through (-1.4326, 0.6736); a ball rests on both ridges and stays on y -1.694 (CAD am-5725, the classifier's rails) | CAD |
| Ramp | 15 degree slope along the wall, ball centres on y -1.6927, 9 slots 0.1255 apart | CAD rails, CM 9.8.2 |
| Gate arm | bar 0.3146 x 0.057 x 0.0128, pivot (0.033, -1.601, 0.1398), axis along the ramp, handle tip 0.075 above the floor | CAD, field-check "3.0 in" |
| Spike marks | x = 0.8961 (GPP), 0.2961 (PGP), -0.3040 (PPG), balls at \|y\| 1.073, 1.200, 1.327 | CAD, CM 10.3.1 |
| Loading zone | balls PGP at x = 1.461, 1.588, 1.715, \|y\| = 1.7215 | CAD, CM 10.3.1 |
| Base zones | 0.4572 square, x 0.7295 to 1.1867, blue y 0.6104 to 1.0676, red y -1.0660 to -0.6088 | CAD |
| Depot | 30 in tape along the foot of the front panel | CAD |
| ARTIFACT | radius 0.06275, 24 purple and 12 green | CAD (the manual allows 4.9 +- 0.25 in) |

**Perimeter.** The manual calls the field "approximately 144 in" inside the walls and says that the size depends on the
perimeter version. The official CAD of the am-0481 perimeter is 141.3 in inside, and the goals, tiles and tag poses are
all dimensioned against it. At +-1.8288 the goals would stand 3.4 cm away from the walls, so the CAD value is used.

## What is physics and what is a rule

- **Solids** are boxes only (`DecodeSolids`); the same list is the scenery of the description, so what is drawn is what
  is collided with. Sloped surfaces are 15 degree slabs; the voids under them are sealed by walls. The floor is one flat
  slab, so there are no tile seams. Tape (`DecodeMarkings`) is in the description only.
- **Goal.** A piece enters through the open top and lands on the internal plate or on the boards, rolls down the plate
  (it falls 15 degrees towards the side wall) over its low edge into the lane of two rails along that wall, and rolls
  single file under the lintel to the SQUARE. The first version had one plane sloping along +X with the diagonal front
  panel as its side wall: that is a throat 0.17 m wide at the archway for 0.1255 m balls, where two balls wedge, and
  balls came to rest inside the goal in about a third of the volleys of four. The CAD has no such throat: no ball
  meets the diagonal panel from inside.
- **Gate.** A real hinged body (`addPivot` of sim-physics): a robot pushes the handle, gravity closes it again in
  about 0.3 to 0.5 s. It counts as open once the arm has turned 0.5 rad, 5 cm of handle travel, which is the "about
  2 in" of the manual's figure. A robot with a flat face reaches 0.8 to 0.9 rad.
- **Ramp.** A constrained queue (`RampLine`), not contacts. A ball that crosses the SQUARE after being inside the goal
  is taken out of the free physics (`holdPiece`) and guided down the ramp by rule; it stays a body in the snapshot and
  rolls. It joins behind the last ball; the first nine are CLASSIFIED, later ones OVERFLOW and roll over the top of the
  row and leave over the gate. While the gate is open the row rolls out nearest ball first; balls that leave become free
  bodies again (`releasePiece`) with the speed they had. Why not contacts: nine balls in a 12.7 cm channel for 12.55 cm
  balls is a stacking problem the sources give no numbers for, and which jams in an engine; no robot can touch a ball
  on the ramp, and in the CAD the gate arm blocks the row by its face, so the balls push along the pivot axis.
- **Counting.** A piece counts once, when it crosses the SQUARE and was inside the goal just before; a piece placed
  on the ramp without passing the goal does not count. Pieces taken off the ramp by the gate may score again.
- **Phases.** AUTO is 30 s from `enterPhase(AUTONOMOUS)`; whatever happens before `enterPhase(TELEOP)`, the 8 s
  transition included, counts as AUTO (there is no `MatchPhase` for the transition). LEAVE is read when AUTO ends and
  the AUTO PATTERN when everything rests, or when TELEOP starts. After `ENDED` the field keeps counting until all
  pieces and the robot rest or 5 s have passed (`DecodeRun.isSettled()`): rolling pieces stop by themselves
  (`PieceSpec.rollingResistance`), so the limit is a safety net and the score settles about 1.5 s after the end in the
  scenario of `DecodeScoringTest`. It then reads TELEOP PATTERN, DEPOT and BASE.
  Until then `score()` is what the referee would read now. PRACTICE scores as TELEOP.
- **Pieces out of the field** that stay outside for 1.5 s go back to the human player of the nearest alliance area.
- **Reserve.** The robot alliance's six alliance-area pieces (4 purple, 2 green, in a seeded order) are handed out by
  `takeFromReserve`; the other alliance's six are never in play. `load-purple` and `load-green` put a reserve piece into
  the first free place of the loading zone in TELEOP or PRACTICE.
- **Seed.** Picks the OBELISK pattern (the obelisk is an entity and is turned to show the tag), the order of the
  alliance-area pieces and a miss of up to 4 mm for every piece placed by hand.
- **Start poses.** Both alliances touching their goal, and in the front launch zone against the audience wall.

## Friction, bounce and bank shots

- **Friction.** The manual says the ARTIFACT is a Gopher ResisDent polypropylene ball and the goal is 0.39 in polypropylene
  sheet, and polypropylene slides on polypropylene at about 0.2 to 0.3. The ARTIFACT's friction is 0.25, and so is that
  of the goal's solids (`Material.PLASTIC`: panels, boards, plate, lintel, rails, ramp floor and walls; the rails are
  aluminium and are treated alike). The engine combines two frictions as the square root of their product: ball on ball
  and ball on goal are 0.25, ball on a foam tile 0.45. Tiles and perimeter keep the engine's 0.8, so the robot's friction
  against them is unchanged. Before it was 0.5 for the ball and 0.8 for every solid. It matters: two balls that land one
  on the other behind the lintel are held by friction. Of 594 volleys of three balls, none jammed with both frictions at
  0.05, 3 to 8 % did at 0.5 and 0.8, about 1 % at 0.25.
- **Bounce.** The ARTIFACT's restitution is 0.35 against everything and the solids have 0. The engine takes the larger
  of the two bodies' values, so a panel can never be less bouncy than the ball; to make it bouncier a solid needs a
  value of its own. The shot sweep is insensitive to the ball's restitution between 0.2 and 0.45 and to the solids'
  up to 0.45 (every legal stance still scores); with 0.6 on every solid 30 % of the shots rebound off the boards and out
  of the goal. 0.35 stays as the estimate of a hollow polypropylene ball on corrugated sheet.
- **Bank shots.** A shot at Hornet's table speeds (`docs/robots/hornet-profile.md`) meets the boards where the end
  board and the side board join, 0.96 to 1.24 m up and at 2 to 3.5 m/s against them, rebounds with about a third of
  that speed and drops onto the plate. The nearer the shot the higher on the board it meets it. A shot 4 to 6 % faster
  than the table's clips the top of the boards (1.365 m) at about 1.4 m and leaves the field; one 6 % slower falls short
  on the front panel. No ball came to rest on a step of the boards in any of the 3762 sweep shots.

## Estimated numbers

Not from a source: ARTIFACT friction 0.25, bounce 0.35, rolling resistance 0.06 and spin damping 0.05/s (a ball rolled at
1 m/s stops after about a metre, 1.7 s); `Material.PLASTIC` friction 0.25; gate arm mass 0.05 kg, centre of mass,
bearing friction 0.002 N m and damper 0.015 N m s/rad (chosen so that gravity closes the arm over its whole travel);
the 0.5 rad opening threshold; ball speed on entering the ramp 0.5 m/s, 5/7 g sin 15 degrees roll acceleration and
1.2 m/s^2 over the stack; the 12 mm gap between the first ball and the closed gate; BASE counts as full when the
robot's outline is within 2.5 cm of the zone's square, partial when it overlaps it.

## Known gaps

- Rigid balls can stack. A ball that lands directly on another behind the lintel rests there against the lintel and the
  wall: the 15 degree pull on the lower ball balances the weight of the upper one. This happened in 3 of the 198 volleys
  of three balls 0.3 s apart that the shot sweep fires. Four balls dropped one after another from random points of the
  opening, the worst case because some land right at the lintel, jammed in 4 to 6 of 40 volleys 0.1 to 0.2 s apart, 1 to 3
  at 0.3 s and 0 to 3 at 0.5 s; the first model of the goal jammed in 15 to 18 of 40 at 0.1 to 0.2 s and in 10 at 0.3 s.
  The geometry follows the CAD; real balls are not rigid, which is what this model cannot say.
- No fouls, ranking points or opposing robot; the opposing human player does nothing.
- The gate arm is a plain bar; the real part has holes and a hook. The rails and the SQUARE plate are not solids.
- G434 (at most six artifacts out of play) and the manual's remark that a very fast shot can skip the ninth slot are
  not modelled.
- The robot's outline for LEAVE and BASE is the rectangle in `FieldSetup` (default 18 in), not its wheel contacts.
- The obelisk's collision body is a 0.2 m square box around its axis, not the prism.

## Checked by tests

`DecodeFieldTest`: equal seeds give equal worlds, 36 artifacts of the right colours, spike and loading layout, tags on
the panels, obelisk faces, start poses, step cost. `DecodeScoringTest`: drop, overflow, pattern, bounces, gate
and robot, phases and settle, human player, returned pieces, depot, base. `GoalFlowTest`: balls dropped on a 4 cm grid
over the opening and thrown at the boards from 1.0 to 1.3 m, both goals, all reach the SQUARE. `RampLineTest`,
`ZonesTest`, `OrientationTest`. `ShotSweepTest` in `simulator` shoots the robot of the Hornet profile into the field from
all over the launch zones (see `docs/robots/hornet-profile.md`). A step with the whole field costs 0.03 to 0.04 ms at
the median and 0.1 to 0.18 ms at the 99th percentile, measured on a Ryzen 9 9950X while other work ran on the machine.
