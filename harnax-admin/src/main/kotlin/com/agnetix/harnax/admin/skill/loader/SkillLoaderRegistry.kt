package com.agnetix.harnax.admin.skill.loader

import com.agnetix.harnax.admin.exception.BizException
import org.springframework.stereotype.Component

@Component
class SkillLoaderRegistry(
    private val loaders: List<SkillLoader>
) {
    fun getLoader(sourceType: String): SkillLoader {
        return loaders.find { it.sourceType == sourceType }
            ?: throw BizException("Unsupported skill source type: $sourceType")
    }
}
