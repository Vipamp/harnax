package com.agnetix.harnax.agent.adaptor

import com.agnetix.harnax.agent.adaptor.token.TokenStat

/**
 * Token 统计适配器接口
 * 用于保存 Token 消耗统计信息
 *
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Project: harnax
 */
fun interface TokenStatAdaptor {

    /**
     * 保存 Token 消耗统计
     *
     * @param tokenStat Token 统计信息
     */
    fun saveTokenStat(tokenStat: TokenStat)
}
