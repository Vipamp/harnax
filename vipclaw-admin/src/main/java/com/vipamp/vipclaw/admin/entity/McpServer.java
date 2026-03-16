package com.vipamp.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MCP 服务实体类
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("mcp_server")
@Schema(description = "MCP 服务实体类")
public class McpServer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MCP ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "MCP ID")
    private Long id;

    /**
     * MCP 名称
     */
    @Schema(description = "MCP 名称")
    private String name;

    /**
     * MCP 描述
     */
    @Schema(description = "MCP 描述")
    private String description;

    /**
     * MCP 类型（stdio/sse/streamablehttp）
     */
    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）")
    private String type;

    /**
     * 执行命令（仅 stdio 类型生效）
     */
    @Schema(description = "执行命令（仅 stdio 类型生效）")
    private String command;

    /**
     * 服务地址（sse/streamablehttp 类型生效）
     */
    @Schema(description = "服务地址（sse/streamablehttp 类型生效）")
    private String url;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    /**
     * 是否可用（0:被删除，1:可用）
     */
    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    private Integer active;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
