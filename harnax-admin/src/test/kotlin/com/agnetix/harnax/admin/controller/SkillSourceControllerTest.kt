package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.SkillSourceCreateRequest
import com.agnetix.harnax.admin.dto.SkillSourceResponse
import com.agnetix.harnax.admin.dto.SkillSourceUpdateRequest
import com.agnetix.harnax.admin.dto.SyncSkillResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.entity.SkillRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Integration tests for SkillSourceController.
 * Uses MockMvc to test the full HTTP layer.
 */
class SkillSourceControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var skillSourceService: SkillSourceService
    private val objectMapper = ObjectMapper()

    private lateinit var testRepository: SkillRepository
    private lateinit var testResponse: SkillSourceResponse

    @BeforeEach
    fun setUp() {
        skillSourceService = mock(SkillSourceService::class.java)
        val controller = SkillSourceController(skillSourceService, "/tmp/harnax-test")
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        testRepository = SkillRepository().apply {
            id = 1L
            tenantId = 1L
            name = "test-source"
            sourceType = "GIT"
            sourceConfig = """{"url":"https://github.com/test/skills"}"""
            version = "1.0.0"
            url = "https://github.com/test/skills"
            branch = "main"
            description = "Test source"
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = SkillSourceResponse(
            id = 1L,
            name = "test-source",
            sourceType = "GIT",
            sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            version = "1.0.0",
            url = "https://github.com/test/skills",
            branch = "main",
            description = "Test source",
            status = 1,
            isPublic = 0,
            creator = "admin",
            createTime = testRepository.createTime.toString(),
            updateTime = testRepository.updateTime.toString(),
        )
    }

    private fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)

    @Nested
    @DisplayName("GET /api/admin/skill-sources/page")
    inner class PageEndpoint {

        @Test
        fun `page should return paginated results`() {
            val page = com.agnetix.harnax.admin.dto.Page<SkillSourceResponse>(
                total = 1L,
                pageNum = 1L,
                pageSize = 10L,
                records = listOf(testResponse),
            )
            `when`(skillSourceService.page(null, null, null, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/skill-sources/page")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `page should handle service error`() {
            `when`(skillSourceService.page(null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            mockMvc.perform(get("/api/admin/skill-sources/page"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to query skill source list: DB error"))
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-sources/{id}")
    inner class GetByIdEndpoint {

        @Test
        fun `getById should return source`() {
            `when`(skillSourceService.getSkillSource(1L)).thenReturn(testRepository)
            `when`(skillSourceService.convertToResponse(testRepository)).thenReturn(testResponse)

            mockMvc.perform(get("/api/admin/skill-sources/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("test-source"))
                .andExpect(jsonPath("$.data.sourceType").value("GIT"))
        }

        @Test
        fun `getById should return error when not found`() {
            `when`(skillSourceService.getSkillSource(999L)).thenReturn(null)

            mockMvc.perform(get("/api/admin/skill-sources/999"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Skill source not found"))
        }
    }

    @Nested
    @DisplayName("POST /api/admin/skill-sources")
    inner class CreateEndpoint {

        @Test
        fun `create should return created source`() {
            val request = SkillSourceCreateRequest(
                name = "new-source",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/new/skills", "branch" to "main"),
            )

            `when`(skillSourceService.createSkillSource(any<SkillSourceCreateRequest>())).thenReturn(testRepository)
            `when`(skillSourceService.convertToResponse(testRepository)).thenReturn(testResponse)

            mockMvc.perform(
                post("/api/admin/skill-sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `create should return error on duplicate name`() {
            val request = SkillSourceCreateRequest(
                name = "existing-source",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillSourceService.createSkillSource(any<SkillSourceCreateRequest>()))
                .thenThrow(BizException("Source name already exists"))

            mockMvc.perform(
                post("/api/admin/skill-sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to create skill source: Source name already exists"))
        }

        @Test
        fun `create NPM source should succeed`() {
            val npmRepo = SkillRepository().apply {
                id = 2L
                name = "npm-source"
                sourceType = "NPM"
                sourceConfig = """{"packageName":"@harnax/skills"}"""
            }
            val npmResponse = SkillSourceResponse(
                id = 2L,
                name = "npm-source",
                sourceType = "NPM",
            )

            val request = SkillSourceCreateRequest(
                name = "npm-source",
                sourceType = "NPM",
                sourceConfig = mapOf("packageName" to "@harnax/skills"),
            )

            `when`(skillSourceService.createSkillSource(any<SkillSourceCreateRequest>())).thenReturn(npmRepo)
            `when`(skillSourceService.convertToResponse(npmRepo)).thenReturn(npmResponse)

            mockMvc.perform(
                post("/api/admin/skill-sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.sourceType").value("NPM"))
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/skill-sources/{id}")
    inner class UpdateEndpoint {

        @Test
        fun `update should return success`() {
            val request = SkillSourceUpdateRequest(
                description = "Updated description",
            )

            `when`(skillSourceService.updateSkillSource(any<Long>(), any<SkillSourceUpdateRequest>())).thenReturn(true)

            mockMvc.perform(
                put("/api/admin/skill-sources/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `update should return error on failure`() {
            val request = SkillSourceUpdateRequest(description = "test")
            `when`(skillSourceService.updateSkillSource(any<Long>(), any<SkillSourceUpdateRequest>())).thenReturn(false)

            mockMvc.perform(
                put("/api/admin/skill-sources/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Update failed"))
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/skill-sources/{id}")
    inner class DeleteEndpoint {

        @Test
        fun `delete should return success`() {
            `when`(skillSourceService.deleteSkillSource(1L)).thenReturn(true)

            mockMvc.perform(delete("/api/admin/skill-sources/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `delete should return error when not found`() {
            `when`(skillSourceService.deleteSkillSource(999L))
                .thenThrow(BizException("Skill source not found"))

            mockMvc.perform(delete("/api/admin/skill-sources/999"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-sources/{id}/fetch")
    inner class FetchSkillsEndpoint {

        @Test
        fun `fetchSkills should return skill list`() {
            val skills = listOf(
                SyncSkillResponse(
                    name = "skill-a",
                    description = "Skill A",
                    skillmd = "# Skill A",
                ),
                SyncSkillResponse(
                    name = "skill-b",
                    description = "Skill B",
                    skillmd = "# Skill B",
                ),
            )
            `when`(skillSourceService.fetchSkills(1L)).thenReturn(skills)

            mockMvc.perform(get("/api/admin/skill-sources/1/fetch"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("skill-a"))
        }

        @Test
        fun `fetchSkills should return error on failure`() {
            `when`(skillSourceService.fetchSkills(1L))
                .thenThrow(RuntimeException("Git clone failed"))

            mockMvc.perform(get("/api/admin/skill-sources/1/fetch"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to fetch skills: Git clone failed"))
        }
    }

    @Nested
    @DisplayName("POST /api/admin/skill-sources/upload")
    inner class UploadEndpoint {

        @Test
        fun `upload should return created source`() {
            val zipFile = MockMultipartFile(
                "file",
                "skills.zip",
                "application/zip",
                byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            )

            `when`(skillSourceService.uploadAndInstall(any(), any(), any()))
                .thenReturn(testRepository)
            `when`(skillSourceService.convertToResponse(testRepository)).thenReturn(testResponse)

            mockMvc.perform(
                multipart("/api/admin/skill-sources/upload")
                    .file(zipFile)
                    .param("name", "uploaded-source"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `upload should return error on failure`() {
            val zipFile = MockMultipartFile(
                "file",
                "bad.zip",
                "application/zip",
                byteArrayOf(0x00),
            )

            `when`(skillSourceService.uploadAndInstall(any(), any(), any()))
                .thenThrow(RuntimeException("Invalid ZIP"))

            mockMvc.perform(
                multipart("/api/admin/skill-sources/upload")
                    .file(zipFile)
                    .param("name", "bad-source"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to upload skills: Invalid ZIP"))
        }
    }
}
