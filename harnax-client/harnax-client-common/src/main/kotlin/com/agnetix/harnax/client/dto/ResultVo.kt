package com.agnetix.harnax.client.dto

/**
 * Unified response result class for the client SDK.
 *
 * This is a standalone copy that does NOT depend on harnax-common's ResultVo,
 * so external consumers don't need to pull in internal Harnax dependencies.
 */
data class ResultVo<T>(
    val code: Int = 200,
    val message: String = "success",
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun isSuccess(): Boolean = this.code == 200

    companion object {
        @JvmStatic
        fun <T> success(): ResultVo<T> = ResultVo(200, "success", null)

        @JvmStatic
        fun <T> success(data: T): ResultVo<T> = ResultVo(200, "success", data)

        @JvmStatic
        fun <T> error(message: String): ResultVo<T> = ResultVo(500, message, null)

        @JvmStatic
        fun <T> error(code: Int, message: String): ResultVo<T> = ResultVo(code, message, null)
    }
}
