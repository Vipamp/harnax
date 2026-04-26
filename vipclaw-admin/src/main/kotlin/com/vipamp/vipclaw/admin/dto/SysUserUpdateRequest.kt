package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 用户更新请求对象
 * 
 * 根据数据库表结构定义:
 * - 所有字段均为可选 (部分更新机制)
 * - username 为只读,不允许修改
 * - 为 null 的字段不会更新
 */
@Schema(description = "用户更新请求对象")
data class SysUserUpdateRequest(
    @Schema(description = "用户 ID", example = "1")
    val id: Long? = null,
    
    @Schema(description = "用户名", example = "zhangsan", accessMode = Schema.AccessMode.READ_ONLY)
    val username: String? = null,
    
    @Schema(description = "昵称")
    @Size(max = 50, message = "昵称长度不能超过 50 个字符")
    val nickname: String? = null,
    
    @Schema(description = "邮箱")
    @Email(message = "邮箱格式不正确")
    val email: String? = null,
    
    @Schema(description = "手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$|^$", message = "手机号格式不正确")
    val phone: String? = null,
    
    @Schema(description = "性别 (0:女 1:男 2:未知)")
    val gender: Int? = null,
    
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,
    
    @Schema(description = "是否是管理员(0:否,1:是)")
    val isAdmin: Int? = null
)
