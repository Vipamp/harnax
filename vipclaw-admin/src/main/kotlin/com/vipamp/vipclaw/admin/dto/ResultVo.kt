package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

/**
 * Unified response result class
 */
@Schema(description = "Unified response result")
data class ResultVo<T>(
    @Schema(description = "Status code", example = "200")
    val code: Int = 200,

    @Schema(description = "Response message", example = "success")
    val message: String = "success",

    @Schema(description = "Response data")
    val data: T? = null,

    @Schema(description = "Timestamp", example = "1704067200000")
    val timestamp: Long = System.currentTimeMillis(),
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * Success response
         */
        @JvmStatic
        fun <T> success(): ResultVo<T> = ResultVo(200, "success", null)

        /**
         * Success response (with data)
         */
        @JvmStatic
        fun <T> success(data: T): ResultVo<T> = ResultVo(200, "success", data)

        /**
         * Success response (with message and data)
         */
        @JvmStatic
        fun <T> success(message: String, data: T): ResultVo<T> = ResultVo(200, message, data)

        /**
         * Error response
         */
        @JvmStatic
        fun <T> error(message: String): ResultVo<T> = ResultVo(500, message, null)

        /**
         * Error response (with status code)
         */
        @JvmStatic
        fun <T> error(code: Int, message: String): ResultVo<T> = ResultVo(code, message, null)
    }

    /**
     * Whether successful
     */
    fun isSuccess(): Boolean = this.code == 200
}
