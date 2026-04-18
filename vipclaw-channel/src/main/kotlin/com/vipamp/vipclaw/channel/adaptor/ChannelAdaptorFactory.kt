package com.vipamp.vipclaw.channel.adaptor

import com.vipamp.vipclaw.channel.ChannelType
import com.vipamp.vipclaw.channel.adaptor.dingtalk.DingTalkAdaptor
import com.vipamp.vipclaw.channel.adaptor.feishu.FeishuAdaptor
import com.vipamp.vipclaw.channel.adaptor.http.HttpAdaptor
import com.vipamp.vipclaw.channel.adaptor.wecom.WeComAdaptor

/**
 * Channel 适配器工厂
 * 根据平台类型获取对应的适配器
 */
object ChannelAdaptorFactory {
    
    private val adaptors: Map<ChannelType, ChannelAdaptor> = mapOf(
        ChannelType.WECOM to WeComAdaptor(),
        ChannelType.FEISHU to FeishuAdaptor(),
        ChannelType.DINGTALK to DingTalkAdaptor(),
        ChannelType.HTTP to HttpAdaptor()
    )
    
    /**
     * 获取指定类型的适配器
     * @param type 平台类型
     * @return 适配器实例
     */
    fun getAdaptor(type: ChannelType): ChannelAdaptor? {
        return adaptors[type]
    }
    
    /**
     * 根据类型代码获取适配器
     * @param typeCode 类型代码
     * @return 适配器实例
     */
    fun getAdaptor(typeCode: String): ChannelAdaptor? {
        val type = ChannelType.fromCode(typeCode) ?: return null
        return getAdaptor(type)
    }
    
    /**
     * 获取所有支持的类型
     */
    fun getSupportedTypes(): List<ChannelType> {
        return adaptors.keys.toList()
    }
}
