package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token 消耗统计实体类
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("token_stats")
@Schema(description = "Token 消耗统计实体类")
public class TokenStats implements Serializable {

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
     * 对话模型 ID
     */
    @Schema(description = "对话模型 ID")
    private Long chatModelId;

    /**
     * 输入 token 数量
     */
    @Schema(description = "输入 token 数量")
    private Long inputToken;

    /**
     * 输出 token 数量
     */
    @Schema(description = "输出 token 数量")
    private Long outputToken;

    /**
     * 总 token 数量
     */
    @Schema(description = "总 token 数量")
    private Long totalToken;

    /**
     * 模型费用（单位：元）
     */
    @Schema(description = "模型费用（单位：元）")
    private BigDecimal fee;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    private LocalDateTime ts;
}
