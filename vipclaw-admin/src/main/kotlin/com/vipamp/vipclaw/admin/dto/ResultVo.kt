package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

/**
 * 统一响应结果类
 */
@Schema(description = "统一响应结果")
data class ResultVo<T>(
    @Schema(description = "状态码", example = "200")
    val code: Int = 200,

    @Schema(description = "响应消息", example = "success")
    val message: String = "success",

    @Schema(description = "响应数据")
    val data: T? = null,

    @Schema(description = "时间戳", example = "1704067200000")
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * 成功响应
         */
        @JvmStatic
        fun <T> success(): ResultVo<T> = ResultVo(200, "success", null)

        /**
         * 成功响应（带数据）
         */
        @JvmStatic
        fun <T> success(data: T): ResultVo<T> = ResultVo(200, "success", data)

        /**
         * 成功响应（带消息和数据）
         */
        @JvmStatic
        fun <T> success(message: String, data: T): ResultVo<T> = ResultVo(200, message, data)

        /**
         * 失败响应
         */
        @JvmStatic
        fun <T> error(message: String): ResultVo<T> = ResultVo(500, message, null)

        /**
         * 失败响应（带状态码）
         */
        @JvmStatic
        fun <T> error(code: Int, message: String): ResultVo<T> = ResultVo(code, message, null)
    }

    /**
     * 判断是否成功
     */
    fun isSuccess(): Boolean = this.code == 200
}
