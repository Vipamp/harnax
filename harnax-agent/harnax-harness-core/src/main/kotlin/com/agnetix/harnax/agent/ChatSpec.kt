package com.agnetix.harnax.agent

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ChatSpec
 * @Project: harnax
 */

class ChatSpec(
    val enableThinking: Boolean,
    val enableSearch: Boolean,
    val enablePlan: Boolean,
    val permissionMode: String = "DEFAULT",
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
    private var permissionMode: String = "DEFAULT"

    fun enableThinking(enableThinking: Boolean) = apply { this.enableThinking = enableThinking }
    fun enableSearch(enableSearch: Boolean) = apply { this.enableSearch = enableSearch }
    fun enablePlan(enablePlan: Boolean) = apply { this.enablePlan = enablePlan }
    fun permissionMode(permissionMode: String) = apply { this.permissionMode = permissionMode }

    fun build() = ChatSpec(
        enableThinking = enableThinking,
        enableSearch = enableSearch,
        enablePlan = enablePlan,
        permissionMode = permissionMode,
    )
}
