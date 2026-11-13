package no.agens.testlab.core.autopilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanValidatorTest {
    @Test
    fun everyLabPlanValidates() {
        val plans = listOf(File("src/jvmTest/resources/plans"), File("../docs/examples")).flatMap { d -> d.walkTopDown().filter { it.extension == "json" && it.parentFile.name == "plans" || it.parentFile == d && it.extension == "json" }.toList() }
        assertTrue(plans.size >= 2, "only ${plans.size} plans")
        for (f in plans) {
            val issues = PlanValidator.validate(Json.parseToJsonElement(f.readText())).filter { it.error }
            assertTrue(issues.isEmpty(), "${f.name}: $issues")
        }
    }

    @Test
    fun bench4NeedsTheFourLoginCredentials() {
        val plan = Json.parseToJsonElement(File("src/jvmTest/resources/plans/bench4-login-books-gifts.json").readText()) as JsonArray
        assertEquals(
            listOf("demo.nnin", "demo.login_one_time_code", "demo.login_password", "demo.app_personal_code"),
            PlanValidator.credRefs(plan),
        )
        val missing = PlanValidator.validate(plan, knownCreds = setOf("demo.nnin")).filter { it.error }
        assertTrue(missing.any { "login_password" in it.message })
    }

    @Test
    fun reportsAllMistakesAtOnce() {
        val plan = Json.parseToJsonElement(
            """[{"goal":""},{"bogus":1},{"type":{}},{"sleep":"x"},{"tap":"Ok","nth":-1},{"wait_for":"Hjem","timeout":5,"typo":1},"nope"]""",
        )
        val issues = PlanValidator.validate(plan)
        assertEquals(listOf(0, 1, 2, 3, 4, 6), issues.filter { it.error }.map { it.index })
        assertTrue(issues.any { it.index == 5 && !it.error && "typo" in it.message })
        assertTrue(PlanValidator.validate(Json.parseToJsonElement("{}")).single().error)
    }
}
