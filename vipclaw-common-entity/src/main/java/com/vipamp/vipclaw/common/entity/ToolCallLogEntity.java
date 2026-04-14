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
 * 工具调用日志实体类
 *
 * @author vipamp
 * @since 2026-04-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("tool_call_log")
@Schema(description = "工具调用日志实体类")
public class ToolCallLogEntity implements Serializable {

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
     * 会话 ID
     */
    @Schema(description = "会话 ID")
    private String sessionId;

    /**
     * 工具名称
     */
    @Schema(description = "工具名称")
    private String toolName;

    /**
     * 工具参数（JSON 格式）
     */
    @Schema(description = "工具参数（JSON 格式）")
    private String args;

    /**
     * 工具执行结果
     */
    @Schema(description = "工具执行结果")
    private String result;

    /**
     * 是否成功（1-成功，0-失败）
     */
    @Schema(description = "是否成功（1-成功，0-失败）")
    private Integer success;

    /**
     * 开始时间戳
     */
    @Schema(description = "开始时间戳")
    private LocalDateTime startTime;

    /**
     * 结束时间戳
     */
    @Schema(description = "结束时间戳")
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    @Schema(description = "执行耗时（毫秒）")
    private Long duration;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    private LocalDateTime ts;
}
