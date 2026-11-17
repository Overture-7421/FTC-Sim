package dev.ftcsim.ui.robot

import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.hardware.UnmappedDevice.Kind
import dev.ftcsim.contracts.session.MotorChoice
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.CameraMount
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MappingModelTest {
    private val motors = listOf(
        MotorChoice("goBILDA-5203-435", "435 rpm"),
        MotorChoice("goBILDA-5203-1150", "1150 rpm"),
        MotorChoice("goBILDA-bare-6000", "bare"),
    )

    private fun motor(name: String) = UnmappedDevice(Kind.MOTOR, name)

    private fun model(turret: Boolean = false) = MappingModel("Team", motors, turret)

    /** Hornet as a student maps it, with a camera that is not the usual one and numbers that do not print short. */
    private fun hornet() = RobotMapping(
        "Hornet",
        listOf(
            MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
            MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
            MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
            MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
            MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
            MotorMapping("shooterRight", Slot.SHOOTER, "goBILDA-5203-1150", true),
            MotorMapping("intake", Slot.INTAKE, "", false),
            MotorMapping("upMotor", Slot.CONVEYOR, "", true),
        ),
        11.3,
        96.5,
        "goBILDA-5203-435",
        1.0,
        "LEFT",
        "UP",
        CameraMount(0.16, 0.0001234, 0.25, 0.0, 20.5, -3.0),
    )

    private fun MappingModel.mapWheels() {
        addFound(listOf("fl", "fr", "bl", "br").map(::motor))
        place("fl", Slot.FRONT_LEFT)
        place("fr", Slot.FRONT_RIGHT)
        place("bl", Slot.REAR_LEFT)
        place("br", Slot.REAR_RIGHT)
    }

    @Test
    fun aMappingThatIsLoadedIsOnThePageUnchangedWithItsMotorsInTheirPlacesAndNothingToSave() {
        val model = model()
        model.load(hornet())

        assertFalse(model.edited)
        assertEquals(hornet(), model.mapping(), "every number and every motor comes back as it was loaded")
        assertEquals(listOf("shooterLeft", "shooterRight"), model.motorsIn(Slot.SHOOTER))
        assertEquals(listOf("frontLeft", "backLeft", "frontRight", "backRight", "shooterLeft", "shooterRight", "intake", "upMotor"), model.found)
        assertTrue(model.unplaced.isEmpty())
        assertEquals("0.0001234", model.form.camera[1])
        assertEquals("96.5", model.form.wheelDiameterMm)
        assertNull(model.blockedReason())
    }

    @Test
    fun theTurretServoAndTheCameraAreOnThePageAndTheEncodersAndTheAxisOfTheFileSurviveASave() {
        val encoders = listOf(RobotMapping.TurretEncoder(0, "rev-tbe-v2", 5.5, 12.0, false), RobotMapping.TurretEncoder(1, "", 44.0 / 7.0, 0.0, true))
        val file = hornet().withMotors(hornet().motors().take(4)).withTurretParts(
            RobotMapping.TurretParts(RobotMapping.ServoMapping("turretServo", "axon-max", false), encoders, 0.02, -0.01, true),
        )
        val model = model(turret = true)
        model.load(file)

        assertEquals("turretServo", model.form.turretServo)
        assertTrue(model.form.cameraOnTurret)
        assertFalse(model.edited)
        assertEquals(file, model.mapping(), "the encoders, the axis and the servo's model come back as they were")

        model.edit { it.copy(servoMirrored = true, cameraOnTurret = false) }
        val saved = model.mapping()!!.turretParts()

        assertTrue(model.edited)
        assertEquals(RobotMapping.ServoMapping("turretServo", "axon-max", true), saved.servo())
        assertEquals(encoders, saved.encoders())
        assertEquals(0.02, saved.axisX())
        assertFalse(saved.cameraOnTurret())

        model.edit { it.copy(turretServo = "") }
        assertNull(model.mapping()!!.turretParts().servo(), "an empty name means a motor turns the turret")
    }

    @Test
    fun theMechanismServosAreOnThePageAndComeBackWithTheirModelsOnASave() {
        val file = hornet().withMotors(hornet().motors().take(4)).withTurretParts(
            RobotMapping.TurretParts(
                null, emptyList(), 0.0, 0.0, false,
                listOf(
                    RobotMapping.MechanismServo(RobotMapping.ServoRole.HOOD, RobotMapping.ServoMapping("hood", "gobilda-2000-torque", true)),
                    RobotMapping.MechanismServo(RobotMapping.ServoRole.STOPPER, RobotMapping.ServoMapping("gate", "", false)),
                ),
            ),
        )
        val model = model(turret = true)
        model.load(file)

        assertEquals("hood", model.form.mechanismServos[RobotMapping.ServoRole.HOOD])
        assertFalse(model.edited)
        assertEquals(file, model.mapping())

        model.edit { it.copy(mechanismServos = it.mechanismServos + (RobotMapping.ServoRole.COLIBRI_LEFT to "armL") + (RobotMapping.ServoRole.STOPPER to "")) }
        val saved = model.mapping()!!.turretParts()

        assertTrue(model.edited)
        assertEquals(RobotMapping.ServoMapping("hood", "gobilda-2000-torque", true), saved.servoFor(RobotMapping.ServoRole.HOOD))
        assertEquals("armL", saved.servoFor(RobotMapping.ServoRole.COLIBRI_LEFT)?.name())
        assertNull(saved.servoFor(RobotMapping.ServoRole.STOPPER), "an empty name takes the servo off")
    }

    @Test
    fun anyChangeToWhatWasLoadedCanBeSavedAndChangingItBackCannot() {
        val model = model()
        model.load(hornet())

        model.remove("intake")
        assertTrue(model.edited)
        assertEquals(listOf("intake"), model.unplaced)
        model.place("intake", Slot.INTAKE)
        assertFalse(model.edited, "put back where it was")

        model.setMirrored("intake", true)
        assertTrue(model.edited)
        model.setMirrored("intake", false)
        assertFalse(model.edited)

        model.edit { it.copy(massKg = "12") }
        assertTrue(model.edited)
        model.edit { it.copy(massKg = "11.30") }
        assertFalse(model.edited, "the same number written another way")
    }

    @Test
    fun aRobotThatHasNoFileIsAlwaysCreatableOnceSomethingIsMapped() {
        val model = model()

        assertTrue(model.edited)
        assertEquals("Map a motor", model.blockedReason())
        model.mapWheels()
        assertNull(model.blockedReason())
        assertEquals(listOf("fl", "fr", "bl", "br"), model.mapping()!!.motors().map { it.name() })
        assertEquals("Team", model.mapping()!!.name())
    }

    @Test
    fun aWheelHoldsOneMotorAndAMechanismAnyNumberAndAMotorIsOnOnePartOnly() {
        val model = model()
        model.addFound(listOf("a", "b", "c").map(::motor))

        model.place("a", Slot.FRONT_LEFT)
        model.place("b", Slot.FRONT_LEFT)
        assertEquals(listOf("b"), model.motorsIn(Slot.FRONT_LEFT), "the motor that was there goes back to the list")
        assertEquals(listOf("a", "c"), model.unplaced)

        model.place("a", Slot.SHOOTER)
        model.place("c", Slot.SHOOTER)
        assertEquals(listOf("a", "c"), model.motorsIn(Slot.SHOOTER))
        model.place("a", Slot.INTAKE)
        assertEquals(listOf("c"), model.motorsIn(Slot.SHOOTER))
        assertEquals(listOf("a"), model.motorsIn(Slot.INTAKE))
        model.place("a", Slot.REAR_LEFT)
        assertTrue(model.motorsIn(Slot.INTAKE).isEmpty())
    }

    @Test
    fun theTurretIsAPlaceOnlyInTheGameThatHasOne() {
        val plain = model(turret = false)
        val aimed = model(turret = true)
        plain.addFound(listOf(motor("aim")))
        aimed.addFound(listOf(motor("aim")))

        assertFalse(Slot.TURRET in plain.slots)
        assertFalse(plain.place("aim", Slot.TURRET))
        assertTrue(Slot.TURRET in aimed.slots)
        assertTrue(aimed.place("aim", Slot.TURRET))
        assertEquals(listOf("aim"), aimed.motorsIn(Slot.TURRET))
        assertTrue(MappingModel.hasTurret("biobuzz") && !MappingModel.hasTurret("decode") && !MappingModel.hasTurret("none"))
    }

    @Test
    fun aTurretMotorOfAFileGoesBackToTheListWhenTheGameHasNoTurretAndTheFileCanBeSavedWithoutIt() {
        val mapping = RobotMapping.empty("Aim").withMotors(listOf(MotorMapping("aim", Slot.TURRET, "", false), MotorMapping("fire", Slot.SHOOTER, "", false)))
        val model = model(turret = false)

        model.load(mapping)

        assertEquals(listOf("aim"), model.unplaced)
        assertEquals(listOf("fire"), model.mapping()!!.motors().map { it.name() })
        assertTrue(model.edited)
    }

    @Test
    fun onlyMotorsAreOfferedAndEachOnceInTheOrderTheCodeAskedForThem() {
        val model = model()

        model.addFound(listOf(motor("b"), UnmappedDevice(Kind.SERVO, "hood"), UnmappedDevice(Kind.I2C, "imu"), motor("a"), motor("b")))

        assertEquals(listOf("b", "a"), model.found)
    }

    @Test
    fun theWheelsShareTheDriveMotorAndOtherPartsHaveTheirOwnWhereTheUsualOneIsStoredAsNone() {
        val model = model()
        model.mapWheels()
        model.addFound(listOf(motor("fly"), motor("roll")))
        model.place("fly", Slot.SHOOTER)
        model.place("roll", Slot.INTAKE)

        assertEquals("goBILDA-bare-6000", model.model("fly"), "a flywheel usually turns on the bare motor")
        assertEquals("goBILDA-5203-435", model.model("roll"))
        model.setModel("fl", "goBILDA-5203-1150")
        assertEquals(listOf("goBILDA-5203-1150"), listOf("fl", "fr", "bl", "br").map { model.model(it) }.distinct(), "one motor on all four")
        assertEquals("goBILDA-5203-1150", model.mapping()!!.driveMotor())
        assertEquals(setOf(""), model.mapping()!!.motors().filter { it.slot().wheel() }.map { it.model() }.toSet())

        model.setModel("roll", "goBILDA-5203-1150")
        assertEquals("goBILDA-5203-1150", model.mapping()!!.motors().first { it.name() == "roll" }.model())
        model.setModel("roll", "goBILDA-5203-435")
        assertEquals("", model.mapping()!!.motors().first { it.name() == "roll" }.model(), "the part's usual motor is not written")
    }

    @Test
    fun aMotorKeepsItsMountingAndModelWhenItMovesToAnotherMechanism() {
        val model = model()
        model.addFound(listOf(motor("m")))
        model.place("m", Slot.CONVEYOR)
        model.setMirrored("m", true)
        model.setModel("m", "goBILDA-5203-1150")

        model.place("m", Slot.INTAKE)

        assertTrue(model.isMirrored("m"))
        assertEquals("goBILDA-5203-1150", model.model("m"))
    }

    @Test
    fun whatIsWrongIsSaidInTheContractsWordsAtTheFieldItBelongsTo() {
        val model = model(turret = true)
        model.mapWheels()

        model.edit { it.copy(name = " ", massKg = "heavy", wheelDiameterMm = "10", hubLogo = "UP", hubUsb = "DOWN", turretRatio = "0") }
        val errors = model.problems()

        assertEquals("The robot has no name", errors[MappingModel.NAME])
        assertEquals("Mass is 1 to 40 kg", errors[MappingModel.MASS])
        assertEquals("Wheel diameter is 40 to 200 mm", errors[MappingModel.WHEEL_DIAMETER])
        assertEquals("Hub logo and USB face at right angles", errors[MappingModel.HUB])
        assertEquals("Turret ratio is above 0", errors[MappingModel.TURRET_RATIO])
        assertEquals("The robot has no name", model.blockedReason())
        assertNull(model.mapping())
    }

    @Test
    fun aTurretRatioThatIsNotUsableDoesNotBlockAGameWithoutATurret() {
        val model = model(turret = false)
        model.mapWheels()

        model.edit { it.copy(turretRatio = "0") }

        assertNull(model.blockedReason())
        assertEquals(1.0, model.mapping()!!.turretRatio())
    }

    @Test
    fun theLimelightMountTakesNumbersOnly() {
        val model = model()
        model.mapWheels()

        model.edit { it.copy(camera = it.camera.mapIndexed { index, text -> if (index == 3) "left" else text }) }

        assertEquals("A number", model.problems()[MappingModel.camera(3)])
        assertNull(model.mapping())
        model.edit { it.copy(camera = it.camera.mapIndexed { index, text -> if (index == 3) "-12,5" else text }) }
        assertEquals(-12.5, model.mapping()!!.camera().yawDegrees(), "a comma is a point")
    }

    @Test
    fun theWheelsAreAllFourOrNoneAndAtMostEightMotorsFitOnTwoHubs() {
        val model = model()
        model.addFound((1..9).map { motor("m$it") })
        model.place("m1", Slot.FRONT_LEFT)

        assertEquals("Map all four wheels or none", model.blockedReason())

        model.remove("m1")
        (1..9).forEach { model.place("m$it", Slot.SHOOTER) }
        assertEquals("At most 8 motors fit on two hubs", model.blockedReason())
        model.remove("m9")
        assertNull(model.blockedReason())
    }

    @Test
    fun aMappingBuiltFromThePageReadsTheTypedNumbersAndTheHubChoice() {
        val model = model(turret = true)
        model.mapWheels()
        model.edit { it.copy(name = " Team robot ", massKg = "12,5", wheelDiameterMm = "100", turretRatio = "3.5", hubLogo = "FORWARD", hubUsb = "RIGHT") }

        val mapping = model.mapping()!!

        assertEquals("Team robot", mapping.name())
        assertEquals(12.5, mapping.massKg())
        assertEquals(100.0, mapping.wheelDiameterMm())
        assertEquals(3.5, mapping.turretRatio())
        assertEquals("FORWARD", mapping.hubLogo())
        assertEquals("RIGHT", mapping.hubUsb())
        assertNotNull(model.motorName("goBILDA-5203-435"))
        assertNull(model.motorName("nonsense"))
        assertEquals(emptyList<String>(), mapping.problems(true))
    }
}
