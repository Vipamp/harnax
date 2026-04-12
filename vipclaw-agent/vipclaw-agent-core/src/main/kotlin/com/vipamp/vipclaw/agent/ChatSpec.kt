package com.vipamp.vipclaw.agent

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ChatSpec
 * @Project: vipclaw
 */
class ChatSpec(
    val enableThinking: Boolean? = null,
    val enableSearch: Boolean? = null
) {
    companion object {
        @JvmStatic
        fun builder() = ChatSpecBuilder()
    }
}

class ChatSpecBuilder {
    private var enableThinking: Boolean? = null
    private var enableSearch: Boolean? = null

    fun enableThinking(enableThinking: Boolean?) = apply { this.enableThinking = enableThinking }
    fun enableSearch(enableSearch: Boolean?) = apply { this.enableSearch = enableSearch }

    fun build() = ChatSpec(
        enableThinking = enableThinking,
        enableSearch = enableSearch
    )
}
