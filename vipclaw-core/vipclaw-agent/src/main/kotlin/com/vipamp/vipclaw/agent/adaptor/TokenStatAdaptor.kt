package com.vipamp.vipclaw.agent.adaptor

import com.vipamp.vipclaw.agent.adaptor.token.TokenStat

/**
 * Token 统计适配器接口
 * 用于保存 Token 消耗统计信息
 *
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Project: vipclaw
 */
fun interface TokenAdaptor {

    /**
     * 保存 Token 消耗统计
     *
     * @param tokenStat Token 统计信息
     */
    fun saveTokenStat(tokenStat: TokenStat)
}
