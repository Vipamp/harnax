package com.vipamp.vipclaw.agent.adaptor

import com.vipamp.vipclaw.agent.adaptor.model.ChatModelConfig

/**
 * 模型配置适配器接口
 * 提供统一的配置获取方式
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: vipclaw
 */
fun interface ChatModelConfigAdaptor {

    fun getConfig(chatModelId: Long): ChatModelConfig?
}
