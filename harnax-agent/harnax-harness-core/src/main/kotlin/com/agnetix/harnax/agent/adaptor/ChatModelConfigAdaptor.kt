package com.agnetix.harnax.agent.adaptor

import com.agnetix.harnax.agent.adaptor.model.ChatModelConfig

/**
 * 模型配置适配器接口
 * 提供统一的配置获取方式
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
fun interface ChatModelConfigAdaptor {

    fun getConfig(chatModelId: Long): ChatModelConfig?
}
