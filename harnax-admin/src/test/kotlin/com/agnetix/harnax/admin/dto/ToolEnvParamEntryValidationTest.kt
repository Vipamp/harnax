package com.agnetix.harnax.admin.dto

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * ToolEnvParamEntry DTO Validation Tests
 * Verifies Bean Validation constraints on ToolEnvParamEntry fields
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolEnvParamEntryValidationTest {

    private lateinit var validator: Validator

    @BeforeAll
    fun setUp() {
        val factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    @Nested
    @DisplayName("envParamName Validation")
    inner class EnvParamNameValidation {

        @Test
        @DisplayName("Valid envParamName passes validation")
        fun `valid envParamName should pass`() {
            val entry = ToolEnvParamEntry(envParamName = "API_KEY")
            val violations = validator.validate(entry)
            assertTrue(violations.isEmpty(), "Expected no violations but got: $violations")
        }

        @Test
        @DisplayName("Empty envParamName fails @NotBlank")
        fun `empty envParamName should fail NotBlank`() {
            val entry = ToolEnvParamEntry(envParamName = "")
            val violations = validator.validate(entry)
            assertTrue(violations.any { it.propertyPath.toString() == "envParamName" })
        }

        @Test
        @DisplayName("Blank envParamName fails @NotBlank")
        fun `blank envParamName should fail NotBlank`() {
            val entry = ToolEnvParamEntry(envParamName = "   ")
            val violations = validator.validate(entry)
            assertTrue(violations.any { it.propertyPath.toString() == "envParamName" })
        }

        @Test
        @DisplayName("envParamName exceeding 200 chars fails @Size")
        fun `envParamName exceeding 200 chars should fail Size`() {
            val entry = ToolEnvParamEntry(envParamName = "A".repeat(201))
            val violations = validator.validate(entry)
            assertTrue(violations.any { it.propertyPath.toString() == "envParamName" })
        }

        @Test
        @DisplayName("envParamName at exactly 200 chars passes")
        fun `envParamName at 200 chars should pass`() {
            val entry = ToolEnvParamEntry(envParamName = "A".repeat(200))
            val violations = validator.validate(entry)
            assertTrue(violations.isEmpty(), "Expected no violations but got: $violations")
        }
    }

    @Nested
    @DisplayName("Default Behavior")
    inner class DefaultBehavior {

        @Test
        @DisplayName("Default values are correct")
        fun `default values should be correct`() {
            val entry = ToolEnvParamEntry(envParamName = "TEST")
            assertFalse(entry.required)
            assertFalse(entry.secret)
            assertNull(entry.defaultValue)
            assertNull(entry.id)
        }

        @Test
        @DisplayName("All fields can be set")
        fun `all fields can be set`() {
            val entry = ToolEnvParamEntry(
                id = 1L,
                envParamName = "DB_HOST",
                description = "Database host",
                required = true,
                secret = true,
                defaultValue = "localhost",
            )
            assertEquals(1L, entry.id)
            assertEquals("DB_HOST", entry.envParamName)
            assertEquals("Database host", entry.description)
            assertTrue(entry.required)
            assertTrue(entry.secret)
            assertEquals("localhost", entry.defaultValue)
        }
    }

    @Nested
    @DisplayName("Data Class Behavior")
    inner class DataClassBehavior {

        @Test
        @DisplayName("copy creates independent instance")
        fun `copy should create independent instance`() {
            val original = ToolEnvParamEntry(envParamName = "KEY1", required = true, secret = false, defaultValue = "val1")
            val copy = original.copy(envParamName = "KEY2", secret = true)
            assertEquals("KEY1", original.envParamName)
            assertFalse(original.secret)
            assertEquals("KEY2", copy.envParamName)
            assertTrue(copy.secret)
            assertEquals("val1", copy.defaultValue) // inherited
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        fun `equals and hashCode should work correctly`() {
            val entry1 = ToolEnvParamEntry(envParamName = "KEY", required = true)
            val entry2 = ToolEnvParamEntry(envParamName = "KEY", required = true)
            val entry3 = ToolEnvParamEntry(envParamName = "OTHER", required = true)
            assertEquals(entry1, entry2)
            assertEquals(entry1.hashCode(), entry2.hashCode())
            assertNotEquals(entry1, entry3)
        }
    }
}
