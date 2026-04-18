package com.vipamp.vipclaw.admin.config

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler
import org.apache.ibatis.reflection.MetaObject
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * MyBatis-Plus 字段自动填充处理器
 */
@Component
class MyMetaObjectHandler : MetaObjectHandler {

    override fun insertFill(metaObject: MetaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime::class.java, LocalDateTime.now())
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime::class.java, LocalDateTime.now())
    }

    override fun updateFill(metaObject: MetaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime::class.java, LocalDateTime.now())
    }
}
