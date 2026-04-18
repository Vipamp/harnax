package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@TableName("sys_user")
@Schema(description = "用户实体类")
class SysUser : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "用户 ID")
    var id: Long = 0

    @Schema(description = "用户名")
    var username: String = ""

    @Schema(description = "密码")
    var password: String = ""

    @Schema(description = "昵称")
    var nickname: String = ""

    @Schema(description = "邮箱")
    var email: String = ""

    @Schema(description = "手机号")
    var phone: String = ""

    @Schema(description = "性别 (0:女 1:男 2:未知)")
    var gender: Int = 2

    @Schema(description = "头像 URL")
    var avatar: String = ""

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否是管理员（0:否，1:是）")
    var isAdmin: Int = 0

    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    var active: Int = 1

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime = LocalDateTime.now()
}
