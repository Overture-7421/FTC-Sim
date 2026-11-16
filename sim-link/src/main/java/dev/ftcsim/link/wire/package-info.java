/**
 * The wire format between the UI process and the worker process: frames of messages, hand-written, compact and
 * deterministic, with no reflection and no serialisation framework. The same state always encodes to the same bytes,
 * and a {@code WorldSnapshot} decodes bit for bit as it was encoded.
 *
 * <h2>Frames</h2>
 *
 * Everything is big-endian. A frame is a 32-bit length {@code n}, {@code 1 <= n <= }{@link
 * dev.ftcsim.link.wire.Protocol#MAX_FRAME_BYTES}, followed by {@code n} payload bytes; the first payload byte is the
 * message kind. A reader allocates nothing before it has checked a length against its bound and against the bytes
 * that remain, so a corrupt stream can cost no more than one frame's worth of memory.
 *
 * <h2>Primitives</h2>
 *
 * <ul>
 *   <li>flag: one byte, 0 or 1; anything else is corrupt
 *   <li>float and double: the IEEE bits ({@code floatToRawIntBits}, {@code doubleToRawLongBits}), so that NaN
 *       payloads and the sign of zero survive
 *   <li>text: a 32-bit length in bytes, at most {@link dev.ftcsim.link.wire.Protocol#MAX_STRING_BYTES}, then strict
 *       UTF-8; text beyond the bound is cut and ends in an ellipsis when written
 *   <li>list: a 32-bit count, at most {@link dev.ftcsim.link.wire.Protocol#MAX_LIST}, then the entries
 *   <li>enumeration: one byte with an explicit code per constant
 * </ul>
 *
 * <h2>Handshake</h2>
 *
 * The launcher listens on {@code 127.0.0.1}, starts the worker with a random token and waits. The worker connects and
 * sends {@code HELLO} (0x01): magic {@code FTCS}, version (16 bits), token (text), process id (64 bits). The launcher
 * answers {@code WELCOME} (0x02): magic, version, or {@code REFUSED} (0x03): reason (text), and closes. A connection
 * whose token is wrong is dropped without an answer. The layout of {@code HELLO} is fixed for ever.
 *
 * <h2>Commands, UI to worker</h2>
 *
 * <table>
 *   <caption>Commands</caption>
 *   <tr><th>kind<th>name<th>fields
 *   <tr><td>0x10<td>Open<td>mapping (name (text); motors, each name (text), slot (one byte: front left, front right, rear left,
 *       rear right, intake, conveyor, shooter, turret), model (text), mirrored (flag); mass, wheel diameter (doubles); drive
 *       motor (text); turret ratio (double); hub logo and USB directions (text); camera x, y, z, yaw, pitch, roll
 *       (doubles)); program, field (text); alliance; seed (64 bits); startX, startY, startHeading, speed (doubles)
 *   <tr><td>0x11<td>Init<td>OpMode name (text)
 *   <tr><td>0x12<td>Start<td>-
 *   <tr><td>0x13<td>Stop<td>-
 *   <tr><td>0x14<td>SetGamepad<td>gamepad (one byte; the worker refuses any but 1 and 2); six floats (left stick x y, right
 *       stick x y, triggers); 15 buttons as the bits of a 16-bit value, in the order of {@code GamepadState}
 *   <tr><td>0x15<td>PerformHumanAction<td>action id (text)
 *   <tr><td>0x16<td>SetPaused<td>flag
 *   <tr><td>0x17<td>SetSpeed<td>double
 *   <tr><td>0x18<td>Reset<td>-
 *   <tr><td>0x19<td>Close<td>-
 *   <tr><td>0x1A<td>ArmMatch<td>autonomous OpMode name, driver-controlled OpMode name (text; the first may be empty)
 *   <tr><td>0x1B<td>StartMatch<td>-
 * </table>
 *
 * <h2>Events, worker to UI</h2>
 *
 * <table>
 *   <caption>Events</caption>
 *   <tr><th>kind<th>name<th>fields
 *   <tr><td>0x40<td>Opened<td>flag and field description; OpModes; robot info (see {@code SessionInfo})
 *   <tr><td>0x41<td>Snapshot<td>step index, simulated time (64 bits each); bodies (see {@code WorldSnapshot})
 *   <tr><td>0x42<td>Status<td>see {@code SessionStatus}; the match is followed by the unplaced names, each a kind (one byte:
 *       motor, servo, I2C device, digital pin, analog pin) and the name (text)
 *   <tr><td>0x43<td>Failed<td>message (text): a session that was asked for could not be opened, or cannot go on
 *   <tr><td>0x44<td>Catalogue<td>fields (id, name, start poses,  human actions); the motors a mapping may name (id,
 *       name) (see {@code Catalogue})
 *   <tr><td>0x45<td>Refused<td>message (text): a command was refused and nothing else changed
 *   <tr><td>0x46<td>Log<td>batches of lines the robot program wrote, which the window shows as its console
 * </table>
 *
 * Fields of records are written in declaration order. A kind that this version does not know, a payload that ends
 * early and a payload with bytes left over are all errors.
 *
 * <h2>Versions</h2>
 *
 * <ul>
 *   <li>1: the first
 *   <li>2: {@code ArmMatch}, {@code StartMatch}, the {@code Catalogue} event, and the match at the end of {@code Status}
 *   <li>3: the {@code Refused} event; {@code Failed} is only for a session that could not open or go on; a gamepad
 *       number is no longer checked by the decoder but by the worker
 *   <li>4: the {@code Catalogue} lists the drivetrain motors a robot profile may name
 *   <li>5: {@code Status} ends with the names the robot program asked the hardware map for that the profile does not
 *       place, so that the robot editor can offer them
 *   <li>6: the {@code Log} event (0x46): batches of lines the robot program wrote
 *   <li>7: {@code Open} carries the robot mapping inline instead of a profile name or path; the {@code Catalogue} no longer
 *       lists profiles
 * </ul>
 */
package dev.ftcsim.link.wire;
