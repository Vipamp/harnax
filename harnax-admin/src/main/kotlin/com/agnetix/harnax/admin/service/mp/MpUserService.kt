package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpChangePasswordRequest
import com.agnetix.harnax.admin.dto.mp.MpUserProfileResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.mapper.SysUserMapper
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MpUserService(
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(MpUserService::class.java)

    fun getProfile(userId: Long): MpUserProfileResponse {
        val user = sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))
        return MpUserProfileResponse(
            userId = user.id,
            username = user.username,
            nickname = user.nickname,
            avatar = user.avatar,
            email = user.email,
            phone = user.phone,
            createTime = user.createTime,
        )
    }

    fun changePassword(userId: Long, request: MpChangePasswordRequest) {
        val user = sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        if (!BCrypt.checkpw(request.oldPassword, user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        val hashedPassword = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
        sysUserMapper.updatePassword(userId, hashedPassword)
        log.info("Password changed for userId: {}", userId)
    }
}
