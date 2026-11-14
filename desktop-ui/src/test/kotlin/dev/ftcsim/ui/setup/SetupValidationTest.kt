package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.practice.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupValidationTest {
    private class Disk(val files: Set<String> = emptySet(), val folders: Set<String> = emptySet()) : PathChecks {
        override fun isFile(path: String) = path in files

        override fun isDirectory(path: String) = path in folders
    }

    private val rules = SetupRules.of(Fixtures.catalogue(), programRequired = false)
    private val strict = rules.copy(programRequired = true)
    private val valid = SetupInput(field = "decode", profile = "hornet-2026")

    private fun problems(input: SetupInput, disk: PathChecks = Disk(), with: SetupRules = rules) =
        SetupValidation.problems(input, with, disk)

    @Test
    fun theRulesAreWhatTheCatalogueSaysAndAFieldTheSimulatorDoesNotKnowKeepsItsId() {
        assertEquals(listOf("none", "decode"), rules.fields.map { it.id() })
        assertEquals(listOf("hornet-2026", "practice-bot"), rules.bundledProfiles)
        assertEquals("DECODE", rules.displayName("decode"))
        assertEquals("old-field", rules.displayName("old-field"))
        assertEquals("decode", rules.defaultField()!!.id(), "the first field with start poses, not the bare floor")
    }

    @Test
    fun theDefaultFormIsFineWhenNoProgramIsRequired() {
        assertTrue(problems(valid).none)
    }

    @Test
    fun theFieldMustBeOneTheSimulatorHas() {
        assertEquals("Choose a field.", problems(valid.copy(field = "")).field)
        assertEquals("This simulator has no field called mars.", problems(valid.copy(field = "mars")).field)
        assertNull(problems(valid.copy(field = "none")).field)
    }

    @Test
    fun aProgramIsAskedForWhenTheSimulatorNeedsOne() {
        val found = problems(valid, with = strict)

        assertEquals("Choose the folder of the prepared program.", found.program)
        assertNull(found.profile)
        assertFalse(found.none)
    }

    @Test
    fun theProgramMustBeAFolderThatExists() {
        val disk = Disk(files = setOf("C:\\a\\file.zip"), folders = setOf("C:\\a\\package"))

        assertNull(problems(valid.copy(program = "C:\\a\\package"), disk, strict).program)
        assertEquals("A prepared program is a folder, not a file.", problems(valid.copy(program = "C:\\a\\file.zip"), disk, strict).program)
        assertEquals("There is no folder at this path.", problems(valid.copy(program = "C:\\a\\missing"), disk, strict).program)
    }

    @Test
    fun aBundledProfileIsKnownByNameAndAnythingWithASeparatorIsAFileThatMustExist() {
        val disk = Disk(files = setOf("C:\\robots\\mine.json"))

        assertNull(problems(valid.copy(profile = "hornet-2026")).profile)
        assertTrue(problems(valid.copy(profile = "falcon")).profile!!.contains("hornet-2026"), "the message lists what is bundled")
        assertNull(problems(valid.copy(profile = "C:\\robots\\mine.json"), disk).profile)
        assertNotNull(problems(valid.copy(profile = "C:\\robots\\other.json"), disk).profile)
        assertNotNull(problems(valid.copy(profile = "robots/mine.json"), disk).profile, "a path with a slash is a path, not a name")
        assertEquals("Choose a robot profile.", problems(valid.copy(profile = "  ")).profile)
    }

    @Test
    fun aFixedSeedMustBeAWholeNumberAndARandomOneIsNeverChecked() {
        assertNull(problems(valid.copy(randomSeed = false, seedText = "12345")).seed)
        assertNull(problems(valid.copy(randomSeed = false, seedText = " -7 ")).seed)
        assertNotNull(problems(valid.copy(randomSeed = false, seedText = "")).seed)
        assertNotNull(problems(valid.copy(randomSeed = false, seedText = "1.5")).seed)
        assertNull(problems(valid.copy(randomSeed = true, seedText = "not a number")).seed)
    }

    @Test
    fun theStartPoseMustBeNumbersInSensibleRange() {
        assertNull(problems(valid.copy(startX = "0,5", startY = "-1.25", headingDegrees = "-90")).startX, "a decimal comma is accepted")
        assertNotNull(problems(valid.copy(startX = "far")).startX)
        assertNotNull(problems(valid.copy(startY = "")).startY)
        assertNotNull(problems(valid.copy(startX = "11")).startX)
        assertNotNull(problems(valid.copy(headingDegrees = "NaN")).heading)
        assertNotNull(problems(valid.copy(startX = "Infinity")).startX)
    }

    @Test
    fun aNamedStartPoseNeedsNoNumbersAndMustBeOneThatTheFieldHas() {
        val named = valid.copy(startPose = "RED goal side", startX = "oops", startY = "", headingDegrees = "NaN")

        assertTrue(problems(named).none, "the numbers of a named pose are the field's, not the form's")
        assertEquals("This field has no start pose called Nowhere.", problems(named.copy(startPose = "Nowhere")).startPose)
        assertNotNull(problems(named.copy(field = "none")).startPose, "the bare floor has no poses")
    }

    @Test
    fun aRequestCarriesEverythingTheFormSaysWithTheHeadingInRadians() {
        val input = valid.copy(
            alliance = Alliance.BLUE, randomSeed = false, seedText = "99", program = " C:\\a\\package ",
            startX = "1,5", startY = "-0.25", headingDegrees = "90",
        )

        val request = SetupValidation.request(input, rules) { error("a fixed seed needs no random one") }!!

        assertEquals(SessionRequest("hornet-2026", "C:\\a\\package", "decode", Alliance.BLUE, 99L, 1.5, -0.25, Math.PI / 2, 1.0), request)
    }

    @Test
    fun aNamedStartPoseGivesTheFieldsOwnNumbers() {
        val request = SetupValidation.request(valid.copy(startPose = "BLUE goal side", alliance = Alliance.BLUE), rules) { 1L }!!

        assertEquals(-1.2, request.startX(), 1e-9)
        assertEquals(-1.4, request.startY(), 1e-9)
        assertEquals(Math.PI / 2, request.startHeading(), 1e-9)
    }

    @Test
    fun aRandomSeedIsChosenWhenTheRequestIsMadeNotBefore() {
        var drawn = 0
        val request = SetupValidation.request(valid, rules) { ++drawn * 1000L }!!

        assertEquals(1000L, request.seed())
        assertEquals(1, drawn)
    }

    @Test
    fun theFormFilledFromARequestRepeatsItExactly() {
        val original = SessionRequest("hornet-2026", "C:\\a\\package", "decode", Alliance.BLUE, 31337L, 0.75, -1.5, Math.toRadians(45.0), 1.0)

        val input = SetupInput.from(original)

        assertEquals(false, input.randomSeed)
        assertEquals("45", input.headingDegrees)
        assertEquals(original, SetupValidation.request(input, rules) { 0L })
    }
}
