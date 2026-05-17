package com.agnetix.harnax.admin.exception

/**
 * Business exception class
 */
class BizException : RuntimeException {

    val code: Int

    constructor(message: String) : super(message) {
        this.code = 400
    }

    constructor(code: Int, message: String) : super(message) {
        this.code = code
    }

    constructor(message: String, cause: Throwable) : super(message, cause) {
        this.code = 400
    }

    constructor(code: Int, message: String, cause: Throwable) : super(message, cause) {
        this.code = code
    }
}
