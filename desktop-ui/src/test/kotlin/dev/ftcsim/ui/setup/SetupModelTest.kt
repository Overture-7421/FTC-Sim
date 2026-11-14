package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.practice.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupModelTest {
    private class Disk : PathChecks {
        override fun isFile(path: String) = false

        override fun isDirectory(path: String) = false
    }

    private val rules = SetupRules.of(Fixtures.catalogue(), programRequired = false)

    private fun model(initial: SetupInput = SetupInput()) = SetupModel(Disk(), { 7L }, initial)

    @Test
    fun aBlankFormTakesTheDefaultFieldTheFirstBundledProfileAndTheFirstPoseWhenTheCatalogueArrives() {
        val model = model()

        model.adopt(rules)

        assertEquals("decode", model.input.field)
        assertEquals("hornet-2026", model.input.profile)
        assertEquals("RED goal side", model.input.startPose)
    }

    @Test
    fun whatThePersonChoseSurvivesTheCatalogueArriving() {
        val model = model(SetupInput(field = "none", alliance = Alliance.BLUE, profile = "C:\\robots\\mine.json", startX = "0.5"))

        model.adopt(rules)

        assertEquals("none", model.input.field)
        assertEquals("C:\\robots\\mine.json", model.input.profile)
        assertNull(model.input.startPose)
        assertEquals("0.5", model.input.startX)
    }

    @Test
    fun aRunReopenedFromTheRecentsSelectsTheStartPoseItsNumbersStandFor() {
        val model = model()
        val atPose = SessionRequest("hornet-2026", "", "decode", Alliance.BLUE, 5L, -1.2, -1.4, Math.PI / 2, 1.0)
        val elsewhere = atPose.let { SessionRequest(it.profile(), "", "decode", Alliance.BLUE, 5L, 0.25, 0.5, 0.0, 1.0) }

        model.use(atPose, rules)
        assertEquals("BLUE goal side", model.input.startPose)
        model.use(elsewhere, rules)
        assertNull(model.input.startPose, "numbers that are no pose of the field stay the person's own")
        assertEquals("0.25", model.input.startX)
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
    fun chosingCustomStartsFromTheNumbersOfThePoseThatWasChosen() {
        val model = model()
        model.adopt(rules)

        model.choosePose(rules, null)

        assertNull(model.input.startPose)
        assertEquals("1.2", model.input.startX)
        assertEquals("-1.4", model.input.startY)
        assertEquals("90", model.input.headingDegrees)
    }

    @Test
    fun aRunCanBeStartedOnlyForTheExactFormAndRulesThatWereChecked() {
        val model = model(SetupInput(field = "decode", profile = "hornet-2026"))
        assertNull(model.request(rules), "nothing was checked yet")
        assertFalse(model.ready(rules))

        model.publish(model.check(model.input, rules))
        assertTrue(model.ready(rules))
        assertEquals(7L, model.request(rules)!!.seed())

        model.edit { it.copy(profile = "falcon") }
        assertFalse(model.ready(rules), "the edit has not been checked yet")
        assertNull(model.request(rules))

        model.publish(model.check(model.input, rules))
        assertFalse(model.ready(rules), "it was checked and is wrong")
        assertNotNull(model.problems(rules).profile)
    }

    @Test
    fun aCheckAgainstOtherRulesNeitherCountsNorShows() {
        val model = model(SetupInput(field = "decode", profile = "hornet-2026"))
        model.publish(model.check(model.input, rules))
        val newer = rules.copy(bundledProfiles = listOf("other"))

        assertFalse(model.ready(newer))
        assertTrue(model.problems(newer).none)
        assertFalse(model.ready(null))
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
