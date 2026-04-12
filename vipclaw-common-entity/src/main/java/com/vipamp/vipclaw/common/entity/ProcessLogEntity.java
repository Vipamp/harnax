package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 处理日志实体类
 *
 * @author vipamp
 * @since 2026-04-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("process_log")
@Schema(description = "处理日志实体类")
public class ProcessLogEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 智能体 ID
     */
    @Schema(description = "智能体 ID")
    private Long agentId;

    /**
     * 智能体名称
     */
    @Schema(description = "智能体名称")
    private String agentName;

    /**
     * 会话 ID
     */
    @Schema(description = "会话 ID")
    private String sessionId;

    /**
     * 日志消息
     */
    @Schema(description = "日志消息")
    private String message;

    /**
     * 日志类型 (INFO/WARN/ERROR)
     */
    @Schema(description = "日志类型 (INFO/WARN/ERROR)")
    private String logType;

    /**
     * 异常堆栈信息
     */
    @Schema(description = "异常堆栈信息")
    private String stackTrace;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    private LocalDateTime ts;
}
