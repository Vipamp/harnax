package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path

@Tag(name = "Skill Source Management")
@RestController
@RequestMapping("/api/admin/skill-sources")
class SkillSourceController(
    private val skillSourceService: SkillSourceService,
    @Value("\${local.tmp-dir:/tmp/harnax}") private val localTmpDir: String,
) {

    private val log = LoggerFactory.getLogger(SkillSourceController::class.java)

    @Operation(summary = "Paginated skill source list")
    @GetMapping("/page")
    fun page(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) sourceType: String?,
        @RequestParam(required = false) status: Int?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<*> {
        return try {
            ResultVo.success(skillSourceService.page(name, sourceType, status, pageNum, pageSize))
        } catch (e: Exception) {
            log.error("Failed to query skill source list", e)
            ResultVo.error("Failed to query skill source list: ${e.message}")
        }
    }

    @Operation(summary = "Get skill source by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<*> {
        return try {
            val source = skillSourceService.getSkillSource(id)
                ?: return ResultVo.error("Skill source not found")
            ResultVo.success(skillSourceService.convertToResponse(source))
        } catch (e: Exception) {
            log.error("Failed to get skill source", e)
            ResultVo.error("Failed to get skill source: ${e.message}")
        }
    }

    @Operation(summary = "Create skill source")
    @PostMapping
    fun create(@Valid @RequestBody request: SkillSourceCreateRequest): ResultVo<*> {
        return try {
            val repository = skillSourceService.createSkillSource(request)
            ResultVo.success(skillSourceService.convertToResponse(repository))
        } catch (e: Exception) {
            log.error("Failed to create skill source", e)
            ResultVo.error("Failed to create skill source: ${e.message}")
        }
    }

    @Operation(summary = "Update skill source")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: SkillSourceUpdateRequest): ResultVo<*> {
        return try {
            val success = skillSourceService.updateSkillSource(id, request)
            if (success) ResultVo.success()
            else ResultVo.error("Update failed")
        } catch (e: Exception) {
            log.error("Failed to update skill source", e)
            ResultVo.error("Failed to update skill source: ${e.message}")
        }
    }

    @Operation(summary = "Delete skill source")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<*> {
        return try {
            val success = skillSourceService.deleteSkillSource(id)
            if (success) ResultVo.success()
            else ResultVo.error("Delete failed")
        } catch (e: Exception) {
            log.error("Failed to delete skill source", e)
            ResultVo.error("Failed to delete skill source: ${e.message}")
        }
    }

    @Operation(summary = "Fetch skills from source")
    @GetMapping("/{id}/fetch")
    fun fetchSkills(@PathVariable id: Long): ResultVo<*> {
        return try {
            val skills = skillSourceService.fetchSkills(id)
            ResultVo.success(skills)
        } catch (e: Exception) {
            log.error("Failed to fetch skills from source", e)
            ResultVo.error("Failed to fetch skills: ${e.message}")
        }
    }

    @Operation(summary = "Upload ZIP and install skills")
    @PostMapping("/upload")
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("name") name: String,
    ): ResultVo<*> {
        return try {
            val tmpDir = Path.of(localTmpDir)
            Files.createDirectories(tmpDir)
            val tmpFile = Files.createTempFile(tmpDir, "skill-upload-", ".zip")
            file.transferTo(tmpFile.toFile())

            try {
                val repository = skillSourceService.uploadAndInstall(
                    tmpFile.toString(),
                    file.originalFilename ?: "unknown.zip",
                    name,
                )
                ResultVo.success(skillSourceService.convertToResponse(repository))
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        } catch (e: Exception) {
            log.error("Failed to upload and install skills", e)
            ResultVo.error("Failed to upload skills: ${e.message}")
        }
    }
}
