package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.ui.practice.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupValidationTest {
    private val rules = SetupRules.of(Fixtures.catalogue())
    private val valid = SetupInput(field = "decode", startPose = "RED goal side")

    @Test
    fun aFormThatNamesAFieldAndAPoseHasNothingWrong() {
        assertTrue(SetupValidation.problems(valid, rules).none)
    }

    @Test
    fun theFieldHasToBeOneTheSimulatorHas() {
        assertEquals("Choose a field.", SetupValidation.problems(valid.copy(field = ""), rules).field)
        assertEquals("This simulator has no field called chess.", SetupValidation.problems(valid.copy(field = "chess"), rules).field)
    }

    @Test
    fun aFixedSeedIsAWholeNumberAndARandomOneNeedsNoText() {
        assertNull(SetupValidation.problems(valid.copy(randomSeed = true, seedText = "junk"), rules).seed)
        assertNull(SetupValidation.problems(valid.copy(randomSeed = false, seedText = " 2026 "), rules).seed)
        assertNotNull(SetupValidation.problems(valid.copy(randomSeed = false, seedText = "12.5"), rules).seed)
    }

    @Test
    fun aNamedPoseHasToBeOneOfTheFieldsAndCustomNumbersAreCheckedInstead() {
        assertEquals("This field has no start pose called Nowhere.", SetupValidation.problems(valid.copy(startPose = "Nowhere"), rules).startPose)

        val custom = valid.copy(startPose = null, startX = "11", startY = "abc", headingDegrees = "left")
        val problems = SetupValidation.problems(custom, rules)
        assertEquals("Enter metres between -10 and 10, such as 0.5.", problems.startX)
        assertEquals("Enter metres between -10 and 10, such as 0.5.", problems.startY)
        assertEquals("Enter the heading in degrees, such as 90.", problems.heading)
        assertTrue(SetupValidation.problems(custom.copy(startX = "0,5", startY = "-2", headingDegrees = "90"), rules).none, "a comma is a decimal point")
    }

    private val robot = RobotMapping.empty("Robot")

    @Test
    fun theRequestTakesThePoseOfTheFieldOrTheNumbersAndConvertsTheHeading() {
        val named = SetupValidation.request(valid, rules, { 9L }, "C:\\prog", robot)!!
        assertEquals(listOf(1.2, -1.4, Math.PI / 2), listOf(named.startX(), named.startY(), named.startHeading()))
        assertEquals(9L, named.seed())
        assertEquals(1.0, named.speed())
        assertEquals(Alliance.RED, named.alliance())

        val custom = SetupValidation.request(
            valid.copy(startPose = null, startX = "0.5", startY = "-0.25", headingDegrees = "180", randomSeed = false, seedText = "5"),
            rules, { 9L }, "p", robot,
        )!!
        assertEquals(listOf(0.5, -0.25, Math.PI), listOf(custom.startX(), custom.startY(), custom.startHeading()))
        assertEquals(5L, custom.seed())

        assertNull(SetupValidation.request(valid.copy(startPose = "Nowhere"), rules, { 9L }, "p", robot))
        assertNull(SetupValidation.request(valid.copy(startPose = null, startX = "x"), rules, { 9L }, "p", robot))
    }

    @Test
    fun theDefaultFieldIsTheFirstGameNotTheBareFloor() {
        assertEquals("decode", rules.defaultField()?.id())
        assertEquals("Bare floor", rules.displayName("none"))
        assertEquals("unknown", rules.displayName("unknown"))
    }
}
