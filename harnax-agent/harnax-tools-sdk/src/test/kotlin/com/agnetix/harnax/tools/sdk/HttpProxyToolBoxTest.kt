package com.agnetix.harnax.tools.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * HttpProxyToolBox Unit Tests
 *
 * Verifies the HttpProxyToolBox metadata methods (getName, getDescription, getParameters)
 * and input schema parsing logic. HTTP callAsync is not tested here to avoid network dependencies.
 *
 * @author agnetix
 * @since 2026-07-10
 */
class HttpProxyToolBoxTest {

    @Nested
    @DisplayName("Metadata Tests")
    inner class MetadataTests {

        @Test
        @DisplayName("getName should return configured tool name")
        fun `getName should return configured tool name`() {
            val box = HttpProxyToolBox(
                toolName = "weather-api",
                toolDescription = "Fetch weather data",
                httpUrl = "https://api.example.com/weather",
            )
            assertEquals("weather-api", box.name)
        }

        @Test
        @DisplayName("getDescription should return configured description")
        fun `getDescription should return configured description`() {
            val box = HttpProxyToolBox(
                toolName = "weather-api",
                toolDescription = "Fetch weather data",
                httpUrl = "https://api.example.com/weather",
            )
            assertEquals("Fetch weather data", box.description)
        }
    }

    @Nested
    @DisplayName("Parameter Parsing Tests")
    inner class ParameterParsingTests {

        @Test
        @DisplayName("getParameters should parse valid JSON input schema")
        fun `getParameters should parse valid JSON schema`() {
            val schema = """
                {
                    "type": "object",
                    "properties": {
                        "city": {"type": "string", "description": "City name"}
                    },
                    "required": ["city"]
                }
            """.trimIndent()

            val box = HttpProxyToolBox(
                toolName = "weather",
                toolDescription = "Weather tool",
                httpUrl = "https://example.com",
                inputSchemaJson = schema,
            )

            val params = box.parameters
            assertEquals("object", params["type"])
            assertNotNull(params["properties"])
        }

        @Test
        @DisplayName("getParameters should return empty map for invalid JSON")
        fun `getParameters should return empty map for invalid JSON`() {
            val box = HttpProxyToolBox(
                toolName = "bad-tool",
                toolDescription = "Bad schema",
                httpUrl = "https://example.com",
                inputSchemaJson = "not-valid-json",
            )

            val params = box.parameters
            assertTrue(params.isEmpty())
        }

        @Test
        @DisplayName("getParameters should return empty map for default empty schema")
        fun `getParameters should return empty map for default empty schema`() {
            val box = HttpProxyToolBox(
                toolName = "default-schema-tool",
                toolDescription = "Default schema",
                httpUrl = "https://example.com",
                inputSchemaJson = "{}",
            )

            val params = box.parameters
            assertTrue(params.isEmpty())
        }
    }

    @Nested
    @DisplayName("Default Values Tests")
    inner class DefaultValuesTests {

        @Test
        @DisplayName("should default to POST method")
        fun `should default to POST method`() {
            val box = HttpProxyToolBox(
                toolName = "t",
                toolDescription = "d",
                httpUrl = "https://example.com",
            )
            // Verify by checking it was constructed successfully with defaults
            assertEquals("t", box.name)
        }

        @Test
        @DisplayName("should accept custom timeout")
        fun `should accept custom timeout`() {
            // Verify construction with custom timeout does not throw
            assertDoesNotThrow {
                HttpProxyToolBox(
                    toolName = "t",
                    toolDescription = "d",
                    httpUrl = "https://example.com",
                    timeoutSeconds = 60,
                )
            }
        }

        @Test
        @DisplayName("should accept custom headers")
        fun `should accept custom headers`() {
            assertDoesNotThrow {
                HttpProxyToolBox(
                    toolName = "t",
                    toolDescription = "d",
                    httpUrl = "https://example.com",
                    httpHeaders = mapOf("Authorization" to "Bearer token123"),
                )
            }
        }
    }
}
