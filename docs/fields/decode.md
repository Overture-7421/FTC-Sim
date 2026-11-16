# DECODE (2025-26) field

Sources, dimensions, rules and estimates of the simulated DECODE field (`dev.ftcsim.game.decode` in `games`): read it to know what the field does and what is only a guess.

`DecodeField` builds the field in an empty `PhysicsWorld`; `DecodeRun` plays its rules. Numbers are metres in the field frame
(origin at the middle, Z up, +X towards the audience, goals at -X) unless they say otherwise. The SDK's tag 20 puts the blue goal
at the -X, -Y corner and the red goal (tag 24) at -X, +Y. A goal is across the field from its alliance area: blue has goal,
CLASSIFIER, gate and depot at -Y, and loading zone, base zone and secret tunnel at +Y. Red mirrors across y = 0; everything is
written once for blue.

## Sources

| What | Source |
| --- | --- |
| Rules, scoring, setup, zones | DECODE Competition Manual TU32: 9.2 FIELD, 9.8 CLASSIFIER, 10.3 setup, 10.5 scoring, G304, G432, G434 |
| Tape and zone placement, tile grid, controlled dimensions | Event field setup guide, field acceptance checklist, field mitigation guide, initial field guide |
| Geometry | AndyMark am-5700_Full STEP (Onshape export of 2025-09-05), read with a script: panels, ramp rails, gate arm, tape, ARTIFACT positions |
| Tags | FTC SDK 11.1.0 `AprilTagGameDatabase`, DECODE library: tags 20 and 24 (6.5 in); the obelisk tags 21 to 23 have no pose there |
| ARTIFACT weight | AndyMark am-3376a product page: 5 in, 0.165 lb (0.0748 kg); the manual gives none |

## Dimensions

| Item | Value | Where from |
| --- | --- | --- |
| Inside of the perimeter | +-1.7945 (141.3 in), walls 0.2785 high (11 in glass) | CAD, am-0481 |
| Goal panels | 27 in (0.6858) wide, 0.9842 high; backboards to 1.3652 | CM figure 9-2, CAD |
| Front (tag) panel | normal at 54.05 degrees, centre (-1.5016, -1.4059) | CAD; the SDK's tag 20 lies 5 mm off its outside face |
| Archway | plate in the plane x = -1.23, 0.781 above the floor (am-5730) | CAD |
| Goal internal ramp | 1 cm polypropylene right-triangle plate (am-5721), hypotenuse on the inside of the front panel; top face z = 1.2136 + 0.268 y, tilted 15 degrees about X towards the side wall; low edge 0.7805 above the floor | CAD |
| Lane | two 1 in square tubes turned 45 degrees (ridge up) at y -1.6368 and -1.7511, 15 degrees down along +X from the end wall; a ball rests on both ridges at y -1.694 (am-5725) | CAD |
| Ramp | 15 degree slope along the wall, ball centres on y -1.6927, 9 slots 0.1255 apart | CAD rails, CM 9.8.2 |
| Gate arm | bar 0.3146 x 0.057 x 0.0128, pivot (0.033, -1.601, 0.1398), axis along the ramp, handle tip 0.075 above the floor | CAD, field-check "3.0 in" |
| Spike marks | x = 0.8961 (GPP), 0.2961 (PGP), -0.3040 (PPG); balls at \|y\| 1.073, 1.200, 1.327 | CAD, CM 10.3.1 |
| Loading zone | PGP balls at x = 1.461, 1.588, 1.715, \|y\| = 1.7215 | CAD, CM 10.3.1 |
| Base zones | 0.4572 square, x 0.7295 to 1.1867; blue y 0.6104 to 1.0676, red y -1.0660 to -0.6088 | CAD |
| Depot | 30 in of tape along the foot of the front panel | CAD |
| ARTIFACT | radius 0.06275, 24 purple and 12 green | CAD (the manual allows 4.9 +- 0.25 in) |

**Perimeter.** The manual says "approximately 144 in" and that it depends on the perimeter version. The CAD is 141.3 in and the
goals, tiles and tag poses are dimensioned against it (at +-1.8288 the goals would stand 3.4 cm from the walls), so the CAD value is used.

## Physics and rules

- **Solids** are boxes only (`DecodeSolids`), the same list as the scenery, so what is drawn is what is collided with. Slopes
  are 15 degree slabs with the voids under them sealed by walls; the floor is one flat slab. Tape (`DecodeMarkings`) is drawn only.
- **Goal.** A piece enters through the open top, lands on the internal plate or boards, rolls into the lane along the side
  wall, and rolls single file under the lintel to the SQUARE. The CAD geometry has no throat where balls wedge (a single plane
  sloping along +X with the diagonal panel as side wall would have one, 0.17 m wide for 0.1255 m balls).
- **Gate.** A hinged body (`PhysicsWorld.addPivot`): a robot pushes the handle, gravity closes it in about 0.3 to 0.5 s. It
  counts as open at 0.5 rad (5 cm of handle travel; the manual says "about 2 in"); a flat-faced robot reaches 0.8 to 0.9 rad.
- **Ramp.** A constrained queue (`RampLine`), not contacts. A ball that crosses the SQUARE after being inside the goal is taken
  out of free physics (`holdPiece`) and guided down the ramp by rule, still a body in the snapshot. It joins behind the last
  ball; the first nine are CLASSIFIED, later ones OVERFLOW over the row and leave over the gate. While the gate is open the row
  rolls out nearest ball first, and leaving balls become free bodies (`releasePiece`) with the speed they had. Not contacts
  because nine balls in a 12.7 cm channel for 12.55 cm balls is a stacking problem the sources give no numbers for and which
  jams in an engine; no robot can touch a ball on the ramp.
- **Counting.** A piece counts once, when it crosses the SQUARE having been inside the goal just before. A piece placed on the
  ramp without passing the goal does not count; pieces the gate takes off the ramp may score again.
- **Phases.** AUTO is 30 s from `enterPhase(AUTONOMOUS)`; whatever happens before `enterPhase(TELEOP)`, the 8 s transition
  included, counts as AUTO. LEAVE is read when AUTO ends, the AUTO PATTERN when everything rests or TELEOP starts. After `ENDED`
  the field keeps counting until pieces and robot rest or 5 s pass (`DecodeRun.isSettled()`), then reads TELEOP PATTERN, DEPOT
  and BASE; until then `score()` is what a referee would read now. PRACTICE scores as TELEOP.
- **Pieces.** One outside the field for 1.5 s goes back to the nearest alliance area's human player. The robot alliance's six
  alliance-area pieces (4 purple, 2 green, seeded order) are handed out by `takeFromReserve`; the other alliance's six are never
  in play. `load-purple` and `load-green` put a reserve piece in the first free loading-zone place in TELEOP or PRACTICE.
- **Seed.** Picks the OBELISK pattern, the order of the alliance-area pieces and a miss of up to 4 mm for each hand-placed
  piece. **Start poses:** both alliances touching their goal, in the front launch zone against the audience wall.

## Friction, bounce and bank shots

- **Friction.** The ARTIFACT (a Gopher ResisDent polypropylene ball) and the goal's solids (`Material.PLASTIC`: panels, boards,
  plate, lintel, rails, ramp floor and walls; the manual says 0.39 in polypropylene sheet, which slides on itself at about 0.2
  to 0.3) have 0.25. The engine combines two frictions as the square root of their product: ball on ball and goal 0.25, on a
  foam tile 0.45; tiles and perimeter keep 0.8, so the robot's friction on them is unchanged. It matters because two balls
  landing one on the other behind the lintel are held by friction: of 594 volleys of three balls none jammed with both
  frictions at 0.05, 3 to 8 % did at 0.5 and 0.8, about 1 % at 0.25.
- **Bounce.** The ARTIFACT's restitution is 0.35 against everything; the solids have 0 (the engine takes the larger). The shot
  sweep is insensitive to the ball's restitution from 0.2 to 0.45 and to the solids' up to 0.45; with 0.6 on every solid 30 %
  of shots rebound out of the goal. 0.35 is the estimate for a hollow polypropylene ball on corrugated sheet.
- **Bank shots.** At Hornet's table speeds (`../robots/hornet-fit.md`) a shot meets the boards where the end and side boards
  join, 0.96 to 1.24 m up at 2 to 3.5 m/s, rebounds with about a third of that speed and drops onto the plate. 4 to 6 % faster
  clips the top of the boards (1.365 m) and leaves the field; 6 % slower falls short on the front panel. No ball rested on a
  step of the boards in 3762 sweep shots.

## Estimated numbers

Not from a source:
- ARTIFACT friction 0.25, bounce 0.35, rolling resistance 0.06, spin damping 0.05/s (a ball rolled at 1 m/s stops after about
  a metre); `Material.PLASTIC` friction 0.25.
- Gate arm: mass 0.05 kg, centre of mass, bearing friction 0.002 N m, damper 0.015 N m s/rad (chosen so gravity closes the arm
  over its whole travel), the 0.5 rad opening threshold.
- Ramp: entry speed 0.5 m/s, roll acceleration 5/7 g sin 15 degrees, 1.2 m/s^2 over the stack, 12 mm gap between the first
  ball and the closed gate.
- BASE: full when the robot's outline is within 2.5 cm of the zone's square, partial when it overlaps it.

## Known gaps

- Rigid balls can stack: one landing directly on another behind the lintel rests against the lintel and wall, because the 15
  degree pull on the lower ball balances the upper one's weight. It happened in 3 of 198 volleys of three balls 0.3 s apart (the
  shot sweep) and, worst case, with four balls dropped at random points of the opening, in 4 to 6 of 40 volleys 0.1 to 0.2 s
  apart, 1 to 3 at 0.3 s, 0 to 3 at 0.5 s. Real balls are not rigid, which this cannot say.
- No fouls, ranking points or opposing robot; the opposing human player does nothing.
- The gate arm is a plain bar (the real part has holes and a hook). The rails and the SQUARE plate are not solids.
- Not modelled: G434 (at most six artifacts out of play) and the manual's remark that a very fast shot can skip the ninth slot.
- The robot's outline for LEAVE and BASE is the rectangle in `FieldSetup` (default 18 in), not its wheel contacts.
- The obelisk's collision body is a 0.2 m square box around its axis, not the prism.
- Performance: a step of the whole field costs 0.03 to 0.04 ms (median), 0.1 to 0.18 ms (99th percentile), on a Ryzen 9 9950X.
