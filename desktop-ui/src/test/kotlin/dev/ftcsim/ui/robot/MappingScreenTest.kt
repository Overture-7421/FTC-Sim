package dev.ftcsim.ui.robot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.hardware.UnmappedDevice.Kind
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.session.MotorChoice
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.project.ScanState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Robot page with synthetic clicks: no real drag is made, so a motor is mapped by clicking it and then its place. */
@OptIn(ExperimentalTestApi::class)
class MappingScreenTest {
    private val motors = listOf(MotorChoice("goBILDA-5203-435", "goBILDA 5203, 435 rpm"), MotorChoice("goBILDA-5203-1150", "goBILDA 5203, 1150 rpm"))
    private val found = listOf("frontLeft", "frontRight", "backLeft", "backRight", "flywheel").map { UnmappedDevice(Kind.MOTOR, it) } +
        listOf(UnmappedDevice(Kind.SERVO, "hood"), UnmappedDevice(Kind.I2C, "colour"))

    private class Taps {
        val scanned = ArrayList<String>()
        var primary = 0
        var skipped = 0
    }

    private fun model(turret: Boolean = false) = MappingModel("Team", motors, turret)

    private fun ComposeUiTest.show(
        model: MappingModel,
        taps: Taps = Taps(),
        scan: ScanState = ScanState.Quiet,
        unmapped: List<UnmappedDevice> = found,
        primary: String = "Create",
        onSkip: (() -> Unit)? = { taps.skipped++ },
    ) = setContent {
        FtcSimTheme(darkTheme = true) {
            MappingScreen(
                model,
                listOf(OpModeDescriptor("TeleOp", "", OpModeKind.TELEOP, "team.TeleOp"), OpModeDescriptor("Auto", "", OpModeKind.AUTONOMOUS, "team.Auto")),
                null, scan, unmapped,
                onScan = { taps.scanned += it }, onCancelScan = {},
                primary = primary, onPrimary = { taps.primary++ }, onSkip = onSkip,
            )
        }
    }

    private fun ComposeUiTest.place(motor: String, slot: String) {
        onNodeWithContentDescription("$motor, motor").performClick()
        waitForIdle()
        onNodeWithContentDescription("Place here: $slot").performClick()
        waitForIdle()
    }

    private fun mapped() = RobotMapping.empty("Team robot").withMotors(
        listOf(
            MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
            MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
            MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
            MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
            MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
            MotorMapping("shooterRight", Slot.SHOOTER, "", false),
        ),
    )

    @Test
    fun beforeAnOpModeIsPickedThePageAsksForOneAndCannotCreateAnything() = runComposeUiTest {
        show(model(), unmapped = emptyList())

        onNodeWithText("Pick an OpMode to list its motors").assertExists()
        onNodeWithText("Create").assertIsNotEnabled()
        onNodeWithText("Map a motor").assertExists()
    }

    @Test
    fun pickingAnOpModeAsksForItToBeScanned() = runComposeUiTest {
        val taps = Taps()
        show(model(), taps, unmapped = emptyList())

        onNodeWithContentDescription("OpMode: Pick an OpMode").performClick()
        onNodeWithText("Auto").performClick()

        assertEquals(listOf("Auto"), taps.scanned)
    }

    @Test
    fun theMotorsTheCodeAsksForAreListedAndTheOtherKindsOfDeviceAreNot() = runComposeUiTest {
        show(model())
        waitForIdle()

        onNodeWithText("Detected").assertExists()
        onNodeWithContentDescription("flywheel, motor").assertExists()
        onNodeWithContentDescription("hood, servo").assertDoesNotExist()
        onNodeWithContentDescription("colour, i2c").assertDoesNotExist()
    }

    @Test
    fun theFixedPartsOfTheGameAreTheSlotsAndTheTurretIsOnlyForTheGameThatHasOne() = runComposeUiTest {
        show(model(turret = false))
        waitForIdle()
        for (slot in listOf("Front left", "Front right", "Rear left", "Rear right", "Intake roller", "Conveyor", "Shooter flywheel")) {
            onNodeWithContentDescription("Place here: $slot").assertExists()
        }
        onNodeWithContentDescription("Place here: Turret").assertDoesNotExist()
        onNodeWithText("Turret ratio").assertDoesNotExist()
        onNodeWithText("Servos").assertDoesNotExist()
    }

    @Test
    fun theTurretAndItsRatioAreThereForBiobuzz() = runComposeUiTest {
        val aimed = model(turret = true)
        show(aimed)
        waitForIdle()

        onNodeWithContentDescription("Place here: Turret").assertExists()
        onNodeWithText("Turret ratio").performScrollTo().assertExists()
        place("flywheel", "Turret")
        assertEquals(listOf("flywheel"), aimed.motorsIn(Slot.TURRET))
    }

    @Test
    fun aMotorIsMappedByClickingItAndThenItsPlace() = runComposeUiTest {
        val model = model()
        show(model)
        waitForIdle()

        place("flywheel", "Shooter flywheel")

        assertEquals(listOf("flywheel"), model.motorsIn(Slot.SHOOTER))
        assertTrue("flywheel" !in model.unplaced)
        onNodeWithContentDescription("Remove flywheel").assertExists()
    }

    @Test
    fun aMotorDraggedWithThePointerAndLetGoOverItsPlaceIsMappedThereAndOneLetGoElsewhereIsNot() = runComposeUiTest {
        val model = model()
        show(model)
        waitForIdle()

        onNodeWithContentDescription("flywheel, motor").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 12f))
            moveTo(Offset(-50f, -50f))
            release()
        }
        waitForIdle()
        assertTrue(model.motorsIn(Slot.SHOOTER).isEmpty(), "let go over nothing, the motor stays in the list")

        val target = onNodeWithContentDescription("Place here: Shooter flywheel").fetchSemanticsNode().boundsInRoot
        val chip = onNodeWithContentDescription("flywheel, motor").fetchSemanticsNode().boundsInRoot
        val toTarget = target.center - chip.center
        onNodeWithContentDescription("flywheel, motor").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 12f))
            moveBy(toTarget - Offset(0f, 12f))
            release()
        }
        waitForIdle()

        assertEquals(listOf("flywheel"), model.motorsIn(Slot.SHOOTER))
    }

    @Test
    fun selectingAMotorAloneMapsNothingAndAGameWithoutATurretOffersNoPlaceForOne() = runComposeUiTest {
        val model = model()
        show(model)
        waitForIdle()

        onNodeWithContentDescription("flywheel, motor").performClick()
        waitForIdle()

        assertEquals("flywheel", model.selected)
        assertTrue(model.motorsIn(Slot.SHOOTER).isEmpty())
        onNodeWithContentDescription("Place here: Turret").assertDoesNotExist()
    }

    @Test
    fun createIsEnabledOnceTheWheelsAreInPlaceAndSkipLeavesWithoutARobot() = runComposeUiTest {
        val taps = Taps()
        show(model(), taps)
        waitForIdle()

        place("frontLeft", "Front left")
        place("frontRight", "Front right")
        place("backLeft", "Rear left")
        onNodeWithText("Create").assertIsNotEnabled()
        onNodeWithText("Map all four wheels or none").assertExists()
        place("backRight", "Rear right")
        waitForIdle()

        onNodeWithText("Create").assertIsEnabled()
        onNodeWithText("Create").performClick()
        onNodeWithText("Skip").performClick()

        assertEquals(1, taps.primary)
        assertEquals(1, taps.skipped)
    }

    @Test
    fun aMappingThatWasLoadedIsOnThePageInItsRowsAndCanBeSavedOnlyAfterAChangeAndHasNoSkip() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithContentDescription("frontLeft, motor").assertExists()
        onNodeWithText("Mass (kg)").assertExists()
        onNodeWithText("Wheel diameter (mm)").assertExists()
        onNodeWithText("Skip").assertDoesNotExist()
        onNodeWithText("Save").assertIsNotEnabled()

        onNodeWithContentDescription("Remove shooterRight").performClick()
        waitForIdle()

        assertEquals(listOf("shooterLeft"), model.motorsIn(Slot.SHOOTER))
        onNodeWithContentDescription("shooterRight, motor").assertExists()
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun theModelOfAMotorIsPickedFromTheCatalogueAndTheWheelsShareIt() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithContentDescription("Motor of frontLeft: goBILDA 5203, 435 rpm").performClick()
        onNodeWithText("goBILDA 5203, 1150 rpm").performClick()
        waitForIdle()

        assertEquals("goBILDA-5203-1150", model.model("backRight"), "one motor on all four wheels")
        onNodeWithContentDescription("Motor of backRight: goBILDA 5203, 1150 rpm").assertExists()
        assertEquals("goBILDA-bare-6000", model.model("shooterLeft"), "a mechanism's motor is its own")
    }

    @Test
    fun theMirroredSwitchOfAMotorTurnsItsPartTheOtherWayRound() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithContentDescription("shooterLeft motor is mounted mirrored").performClick()
        waitForIdle()

        assertTrue(model.isMirrored("shooterLeft"))
        assertFalse(model.isMirrored("shooterRight"))
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun theRobotsNumbersAreTypedAndAWrongOneIsSaidAndBlocksTheButton() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithText("Mass (kg)").performScrollTo().performTextReplacement("12")
        waitForIdle()
        assertEquals("12", model.form.massKg)
        onNodeWithText("Save").assertIsEnabled()

        onNodeWithText("Wheel diameter (mm)").performScrollTo().performTextReplacement("5")
        waitForIdle()

        onAllNodesWithText("Wheel diameter is 40 to 200 mm").assertCountEquals(2)
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun theHubsMountingIsChosenAndLogoAndUsbMustFaceAtRightAngles() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithContentDescription("Hub logo faces: up").performScrollTo().performClick()
        onNodeWithText("backward").performClick()
        waitForIdle()

        assertEquals("BACKWARD", model.form.hubLogo)
        onNodeWithText("Hub logo and USB face at right angles").assertExists()
        onNodeWithText("Save").assertIsNotEnabled()

        onNodeWithContentDescription("Hub USB faces: forward").performScrollTo().performClick()
        onNodeWithText("left").performClick()
        waitForIdle()

        assertEquals("LEFT", model.form.hubUsb)
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun theLimelightsMountOpensInAWindowOfItsOwnWithItsSixNumbers() = runComposeUiTest {
        val model = model()
        model.load(mapped())
        show(model, unmapped = emptyList(), primary = "Save", onSkip = null)
        waitForIdle()

        onNodeWithText("X (m)").assertDoesNotExist()
        onNodeWithContentDescription("Settings of Limelight").performScrollTo().performClick()
        waitForIdle()
        for (label in MappingModel.CAMERA_LABELS) onNodeWithText(label).assertExists()
        onNodeWithContentDescription("Limelight Yaw (deg)").performTextReplacement("15")
        waitForIdle()

        assertEquals("15", model.form.camera[3])
        assertEquals(15.0, model.mapping()!!.camera().yawDegrees())
        onNodeWithText("Done").performClick()
        waitForIdle()
        onNodeWithText("X (m)").assertDoesNotExist()
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun aScanInProgressIsSaidInAFewWords() = runComposeUiTest {
        show(model(), scan = ScanState.Scanning("TeleOp", automatic = false), unmapped = emptyList())

        onNodeWithText("Reading TeleOp").assertExists()
    }
}
