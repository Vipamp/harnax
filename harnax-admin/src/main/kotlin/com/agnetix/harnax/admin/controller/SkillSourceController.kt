package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.admin.util.ApiErrors
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
    @Value("\${local.tmp-dir:/home/harnax/skills}") private val localTmpDir: String,
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
    ): ResultVo<Page<SkillSourceResponse>> = try {
        ResultVo.success(skillSourceService.page(name, sourceType, status, pageNum, pageSize))
    } catch (e: Exception) {
        log.error("Failed to query skill source list", e)
        ResultVo.error("Failed to query skill source list: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Get active skill sources", description = "Tenant sources plus the shared builtin one, for pickers")
    @GetMapping("/active")
    fun listActive(): ResultVo<List<SkillSourceResponse>> = try {
        ResultVo.success(skillSourceService.listActive().map { SkillSourceResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get active skill sources", e)
        ResultVo.error("Failed to get active skill sources: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Get skill source by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<SkillSourceResponse> {
        return try {
            val source = skillSourceService.getSkillSource(id)
                ?: return ResultVo.error("Skill source not found")
            ResultVo.success(skillSourceService.convertToResponse(source))
        } catch (e: Exception) {
            log.error("Failed to get skill source", e)
            ResultVo.error("Failed to get skill source: ${ApiErrors.message(e, UNKNOWN)}")
        }
    }

    @Operation(summary = "Create skill source")
    @PostMapping
    fun create(@Valid @RequestBody request: SkillSourceCreateRequest): ResultVo<SkillSourceInstallResponse> = try {
        ResultVo.success(skillSourceService.createSkillSource(request))
    } catch (e: Exception) {
        log.error("Failed to create skill source", e)
        ResultVo.error("Failed to create skill source: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Re-install skills from the source")
    @PostMapping("/{id}/install")
    fun install(@PathVariable id: Long): ResultVo<SkillInstallResponse> = try {
        ResultVo.success(skillSourceService.installSkills(id))
    } catch (e: Exception) {
        log.error("Failed to install skills from source {}", id, e)
        ResultVo.error("Failed to install skills: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Update skill source")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: SkillSourceUpdateRequest): ResultVo<Void> = try {
        val success = skillSourceService.updateSkillSource(id, request)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Update failed")
        }
    } catch (e: Exception) {
        log.error("Failed to update skill source", e)
        ResultVo.error("Failed to update skill source: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Delete skill source")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<Void> = try {
        val success = skillSourceService.deleteSkillSource(id)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Delete failed")
        }
    } catch (e: Exception) {
        log.error("Failed to delete skill source", e)
        ResultVo.error("Failed to delete skill source: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Toggle skill source status")
    @PutMapping("/toggle/{id}")
    fun toggleStatus(@PathVariable id: Long, @RequestParam status: Int): ResultVo<Void> = try {
        if (skillSourceService.toggleStatus(id, status)) {
            ResultVo.success()
        } else {
            ResultVo.error("Toggle failed")
        }
    } catch (e: Exception) {
        log.error("Failed to toggle skill source status", e)
        ResultVo.error("Failed to toggle skill source status: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Fetch skills from source")
    @GetMapping("/{id}/fetch")
    fun fetchSkills(@PathVariable id: Long): ResultVo<List<SyncSkillResponse>> = try {
        val skills = skillSourceService.fetchSkills(id)
        ResultVo.success(skills)
    } catch (e: Exception) {
        log.error("Failed to fetch skills from source", e)
        ResultVo.error("Failed to fetch skills: ${ApiErrors.message(e, UNKNOWN)}")
    }

    @Operation(summary = "Upload ZIP and install skills")
    @PostMapping("/upload")
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("name") name: String,
    ): ResultVo<SkillSourceInstallResponse> = try {
        val tmpDir = Path.of(localTmpDir)
        Files.createDirectories(tmpDir)
        val tmpFile = Files.createTempFile(tmpDir, "skill-upload-", ".zip")
        try {
            // Inside the guard on purpose: a `transferTo` failure used to leave the empty temp file
            // behind, because the cleanup only covered the install step
            file.transferTo(tmpFile.toFile())
            ResultVo.success(
                skillSourceService.uploadAndInstall(
                    tmpFile.toString(),
                    file.originalFilename ?: "unknown.zip",
                    name,
                ),
            )
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    } catch (e: Exception) {
        log.error("Failed to upload and install skills", e)
        ResultVo.error("Failed to upload skills: ${ApiErrors.message(e, UNKNOWN)}")
    }

    private companion object {
        /** Shown when the caught exception carries no message at all. */
        const val UNKNOWN = "unknown error"
    }
}
