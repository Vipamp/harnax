package com.agnetix.harnax.harness.spring

import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.ToolCallLogAdaptor
import com.agnetix.harnax.agent.session.SessionConfig
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import io.agentscope.harness.agent.IsolationScope
import io.minio.MinioClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

// ===== Configuration Properties =====

@ConfigurationProperties(prefix = "harness.minio")
class MinioProperties {
    var enabled: Boolean = false
    var endpoint: String = "http://localhost:9000"
    var accessKey: String = "minioadmin"
    var secretKey: String = "minioadmin"
    var snapshotBucket: String = "harnax-snapshots"
    var storeBucket: String = "harnax-store"
    var snapshotPrefix: String = "snapshots/"
    var storePrefix: String = "store/"
}

@ConfigurationProperties(prefix = "harness.sandbox")
class SandboxProperties {
    var enabled: Boolean = false
    var image: String = "python:3.11-slim"
    var workspaceRoot: String = "/workspace"
    var isolationScope: String = "SESSION"
    var keepAlive: Boolean = false
}

@ConfigurationProperties(prefix = "harness")
class HarnessProperties {
    var enableWorkspaceContext: Boolean = false
    var enableMemoryHooks: Boolean = false
    var enableSessionPersistence: Boolean = true
}

// ===== Auto-Configuration =====

@AutoConfiguration
@EnableConfigurationProperties(MinioProperties::class, SandboxProperties::class, HarnessProperties::class)
class HarnessAutoConfiguration {

    /**
     * MinIO client bean — only created when `harness.minio.enabled=true`.
     */
    @Bean
    @ConditionalOnProperty(prefix = "harness.minio", name = ["enabled"], havingValue = "true")
    fun minioClient(props: MinioProperties): MinioClient = MinioClient.builder()
        .endpoint(props.endpoint)
        .credentials(props.accessKey, props.secretKey)
        .build()

    /**
     * [MinioConfig] bean — only created when `harness.minio.enabled=true`.
     * Ensures buckets exist on startup.
     */
    @Bean
    @ConditionalOnProperty(prefix = "harness.minio", name = ["enabled"], havingValue = "true")
    fun minioConfig(props: MinioProperties, client: MinioClient): MinioConfig {
        val config = MinioConfig(
            endpoint = props.endpoint,
            accessKey = props.accessKey,
            secretKey = props.secretKey,
            snapshotBucket = props.snapshotBucket,
            storeBucket = props.storeBucket,
            snapshotPrefix = props.snapshotPrefix,
            storePrefix = props.storePrefix,
        )
        config.ensureBuckets(client)
        return config
    }

    /**
     * [HarnessConfig] bean — always available.
     */
    @Bean
    fun harnessConfig(
        harnessProps: HarnessProperties,
        sandboxProps: SandboxProperties,
    ): HarnessConfig = HarnessConfig(
        sandbox = SandboxConfig(
            enabled = sandboxProps.enabled,
            image = sandboxProps.image,
            workspaceRoot = sandboxProps.workspaceRoot,
            isolationScope = runCatching { IsolationScope.valueOf(sandboxProps.isolationScope) }
                .getOrDefault(IsolationScope.SESSION),
            keepAlive = sandboxProps.keepAlive,
        ),
        enableWorkspaceContext = harnessProps.enableWorkspaceContext,
        enableMemoryHooks = harnessProps.enableMemoryHooks,
        enableSessionPersistence = harnessProps.enableSessionPersistence,
    )

    /**
     * [HarnessAgentLauncher] bean — the distributed agent launcher.
     *
     * This bean replaces [com.agnetix.harnax.agent.AscopeAgentLauncher] when the harness
     * module is on the classpath. Inject all required adaptors via Spring DI.
     */
    @Bean
    fun harnessAgentLauncher(
        chatModelConfigAdaptor: ChatModelConfigAdaptor,
        mcpConfigAdaptor: McpConfigAdaptor,
        skillAdaptor: SkillAdaptor,
        tokenStatAdaptor: TokenStatAdaptor,
        sessionConfig: SessionConfig,
        processLogAdaptor: ProcessLogAdaptor,
        toolCallLogAdaptorProvider: ObjectProvider<ToolCallLogAdaptor>,
        planNoteAdaptor: PlanNoteAdaptor,
        harnessConfig: HarnessConfig,
        @Value("\${local.tmp-dir:/tmp/harnax-agent}") tmpDir: String,
        minioConfig: MinioConfig?,
    ): HarnessAgentLauncher {
        val toolCallLogAdaptor = toolCallLogAdaptorProvider.ifAvailable
            ?: ToolCallLogAdaptor { /* no-op */ }
        return HarnessAgentLauncher.initLauncher(
            chatModelConfigAdaptor = chatModelConfigAdaptor,
            mcpConfigAdaptor = mcpConfigAdaptor,
            skillAdaptor = skillAdaptor,
            tokenStatAdaptor = tokenStatAdaptor,
            sessionConfig = sessionConfig,
            processLogAdaptor = processLogAdaptor,
            toolCallLogAdaptor = toolCallLogAdaptor,
            planNoteAdaptor = planNoteAdaptor,
            workspaceRoot = java.nio.file.Path.of(tmpDir),
            harnessConfig = harnessConfig,
            minioConfig = minioConfig,
        )
    }
}
