package com.agnetix.harnax.scheduler.support

/**
 * Business exception of this service: an expected refusal, carrying the code the caller sees.
 *
 * Same shape as `harnax-admin`'s `BizException`, which is what the scheduled-task endpoints threw before
 * this domain moved here. [SchedulerWebConfig] turns it into a `ResultVo.error(code, message)` body, so an
 * exception of this class is never a 500 and never a stack trace in the response.
 */
class SchedulerBizException(
    val code: Int,
    override val message: String,
) : RuntimeException(message) {

    /** A bare refusal is a bad request, exactly like admin's `BizException(message)`. */
    constructor(message: String) : this(400, message)
}
