package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.practice.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupModelTest {
    private val rules = SetupRules.of(Fixtures.catalogue())

    private fun model(initial: SetupInput = SetupInput()) = SetupModel({ 7L }, initial)

    @Test
    fun aBlankFormTakesTheDefaultFieldAndTheFirstPoseWhenTheCatalogueArrives() {
        val model = model()

        model.adopt(rules)

        assertEquals("decode", model.input.field)
        assertEquals("RED goal side", model.input.startPose)
    }

    @Test
    fun whatThePersonChoseSurvivesTheCatalogueArriving() {
        val model = model(SetupInput(field = "none", alliance = Alliance.BLUE, startX = "0.5"))

        model.adopt(rules)

        assertEquals("none", model.input.field)
        assertNull(model.input.startPose)
        assertEquals("0.5", model.input.startX)
    }

    @Test
    fun aRunLoadedFromWhatTheProjectRememberedSelectsTheStartPoseItsNumbersStandFor() {
        val model = model()
        val atPose = SessionRequest(RobotMapping.empty("Robot"), "", "decode", Alliance.BLUE, 5L, -1.2, -1.4, Math.PI / 2, 1.0)
        val elsewhere = SessionRequest(RobotMapping.empty("Robot"), "", "decode", Alliance.BLUE, 5L, 0.25, 0.5, 0.0, 1.0)

        model.load(SetupInput.from(atPose), rules)
        assertEquals("BLUE goal side", model.input.startPose)
        assertFalse(model.input.randomSeed, "a run that is remembered repeats")
        assertEquals("5", model.input.seedText)
        model.load(SetupInput.from(elsewhere), rules)
        assertNull(model.input.startPose, "numbers that are no pose of the field stay the person's own")
        assertEquals("0.25", model.input.startX)
    }

    @Test
    fun loadingAFormWithoutAFieldGivesItTheDefaultFieldAndLoadingWithoutRulesKeepsItAsItIs() {
        val model = model()

        model.load(SetupInput(alliance = Alliance.BLUE), null)
        assertEquals("", model.input.field)

        model.load(SetupInput(alliance = Alliance.BLUE), rules)
        assertEquals("decode", model.input.field)
        assertEquals("BLUE goal side", model.input.startPose)
    }

    @Test
    fun changingTheAllianceMovesAPoseOfTheOldAllianceToTheFirstOfTheNewOne() {
        val model = model()
        model.adopt(rules)
        assertEquals("RED goal side", model.input.startPose)

        model.chooseAlliance(rules, Alliance.BLUE)

        assertEquals("BLUE goal side", model.input.startPose)
    }

    @Test
    fun aPoseBothAlliancesMayUseStaysWhenTheAllianceChanges() {
        val model = model()
        model.adopt(rules)
        model.choosePose(rules, "Centre")

        model.chooseAlliance(rules, Alliance.BLUE)

        assertEquals("Centre", model.input.startPose)
    }

    @Test
    fun changingTheFieldChangesTheStartPosesOnOfferAndABareFloorHasNone() {
        val model = model()
        model.adopt(rules)

        model.chooseField(rules, "none")
        assertNull(model.input.startPose)
        assertTrue(rules.startPoses(model.input.field, model.input.alliance).isEmpty())

        model.chooseField(rules, "decode")
        assertEquals("RED goal side", model.input.startPose, "an untouched form takes the first pose of a field that has some")
    }

    @Test
    fun numbersThePersonTypedAreKeptWhenTheFieldChanges() {
        val model = model()
        model.adopt(rules)
        model.choosePose(rules, null)
        model.edit { it.copy(startX = "0.25") }

        model.chooseField(rules, "none")
        model.chooseField(rules, "decode")

        assertNull(model.input.startPose)
        assertEquals("0.25", model.input.startX)
    }

    @Test
    fun choosingCustomStartsFromTheNumbersOfThePoseThatWasChosen() {
        val model = model()
        model.adopt(rules)

        model.choosePose(rules, null)

        assertNull(model.input.startPose)
        assertEquals("1.2", model.input.startX)
        assertEquals("-1.4", model.input.startY)
        assertEquals("90", model.input.headingDegrees)
    }

    @Test
    fun aRunCanBeRequestedOnlyForAFormThatHasNothingWrongAndCarriesTheProjectsProgramAndMapping() {
        val model = model(SetupInput(field = "decode"))
        val robot = RobotMapping.empty("Team robot")
        assertNull(model.request(null, "C:\\prog", robot), "the simulator has not said what it has")

        val request = model.request(rules, "C:\\prog", robot)!!
        assertEquals("C:\\prog", request.program())
        assertEquals(robot, request.mapping())
        assertEquals(7L, request.seed(), "the random seed is drawn when the run starts")

        model.edit { it.copy(randomSeed = false, seedText = "x") }
        assertFalse(model.ready(rules))
        assertNull(model.request(rules, "C:\\prog", robot))
        assertEquals("The seed is a whole number, such as 12345.", model.problems(rules).seed)
        assertTrue(model.problems(null).none, "before the simulator has spoken nothing is wrong yet")
    }

    @Test
    fun theStartPosesOfAFieldFollowTheAlliancesByTheirNames() {
        val poses = listOf(
            StartPose("RED left", 1.0, 1.0, 0.0), StartPose("Blue right", -1.0, 1.0, 0.0),
            StartPose("Middle", 0.0, 0.0, 0.0),
        )

        assertEquals(listOf("RED left", "Middle"), StartPoses.forAlliance(poses, Alliance.RED).map { it.name() })
        assertEquals(listOf("Blue right", "Middle"), StartPoses.forAlliance(poses, Alliance.BLUE).map { it.name() })
    }

    @Test
    fun aFieldWhosePosesNameNoAllianceOffersThemAllAndOneWhosePosesAreAllForTheOtherAllianceDoesToo() {
        val unnamed = listOf(StartPose("Left", 1.0, 0.0, 0.0), StartPose("Right", -1.0, 0.0, 0.0))
        val onlyBlue = listOf(StartPose("BLUE wall", 1.0, 0.0, 0.0))

        assertEquals(unnamed, StartPoses.forAlliance(unnamed, Alliance.RED))
        assertEquals(onlyBlue, StartPoses.forAlliance(onlyBlue, Alliance.RED))
        assertEquals(Alliance.BLUE, StartPoses.allianceOf(StartPose("blue: far side", 0.0, 0.0, 0.0)))
        assertNull(StartPoses.allianceOf(StartPose("Redwood corner", 0.0, 0.0, 0.0)), "only the whole first word counts")
    }

    @Test
    fun numbersMatchAPoseWhenTheyAreItsOwnWithinRoundingAndHeadingsWrapAround() {
        val poses = listOf(StartPose("RED", 1.2, -1.4, Math.PI / 2))

        assertEquals("RED", StartPoses.matching(poses, 1.2, -1.4, Math.PI / 2)!!.name())
        assertEquals("RED", StartPoses.matching(poses, 1.2, -1.4, Math.PI / 2 + 2 * Math.PI)!!.name())
        assertNull(StartPoses.matching(poses, 1.21, -1.4, Math.PI / 2))
    }
}
