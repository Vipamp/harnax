package com.vipamp.vipclaw.agent

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ChatSpec
 * @Project: vipclaw
 */
class ChatSpec(
    val enableThinking: Boolean,
    val enableSearch: Boolean,
    val enablePlan: Boolean,
) {
    companion object {
        @JvmStatic
        fun builder() = ChatSpecBuilder()
    }
}

class ChatSpecBuilder {
    private var enableThinking: Boolean = false
    private var enableSearch: Boolean = false
    private var enablePlan: Boolean = false

    fun enableThinking(enableThinking: Boolean) = apply { this.enableThinking = enableThinking }
    fun enableSearch(enableSearch: Boolean) = apply { this.enableSearch = enableSearch }
    fun enablePlan(enablePlan: Boolean) = apply { this.enablePlan = enablePlan }

    fun build() = ChatSpec(
        enableThinking = enableThinking,
        enableSearch = enableSearch,
        enablePlan = enablePlan
    )
}
