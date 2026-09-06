package com.agnetix.harnax.harness.spring

import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.session.SessionConfig
import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.harness.mcp.PlaintextMcpConfigDecryptor
import com.agnetix.harnax.harness.output.MinioOutputFileStore
import com.agnetix.harnax.harness.output.OutputFileDetector
import com.agnetix.harnax.harness.output.OutputFileStore
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolConfigAdaptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
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
    var outputBucket: String = "harnax-output"
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
    var network: String? = null
    var cliPluginsEnabled: Boolean = false
    var pluginImage: String = "harnax-sandbox:latest"
    var pluginAdminUrl: String = ""
    var pluginInternalSecret: String = ""
}

@ConfigurationProperties(prefix = "harness")
class HarnessProperties {
    var enableWorkspaceContext: Boolean = false
    var enableMemoryHooks: Boolean = false
    var enableSessionPersistence: Boolean = true
}

@ConfigurationProperties(prefix = "harness.output-detection")
class OutputDetectionProperties {
    var enabled: Boolean = true
    var maxFileSize: Long = 50 * 1024 * 1024 // 50MB
    var maxFilesPerResponse: Int = 5
    var allowedExtensions: List<String> = listOf(
        "pptx", "ppt", "xlsx", "xls", "csv",
        "docx", "doc", "pdf",
        "png", "jpg", "jpeg", "gif", "svg",
        "zip", "tar", "gz",
        "mp3", "mp4", "wav",
        "html", "json",
    )
    var adminBaseUrl: String = "" // e.g. "http://localhost" or empty for relative URLs
}

// ===== Auto-Configuration =====

@AutoConfiguration
@EnableConfigurationProperties(MinioProperties::class, SandboxProperties::class, HarnessProperties::class, OutputDetectionProperties::class)
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
            outputBucket = props.outputBucket,
            snapshotPrefix = props.snapshotPrefix,
            storePrefix = props.storePrefix,
        )
        config.ensureBuckets(client)
        return config
    }

    /**
     * [OutputFileDetector] bean — detects new files in sandbox workspace.
     * Only created when output detection is enabled and MinIO is available.
     */
    @Bean
    @ConditionalOnProperty(prefix = "harness.output-detection", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "harness.minio", name = ["enabled"], havingValue = "true")
    fun outputFileDetector(props: OutputDetectionProperties): OutputFileDetector = OutputFileDetector(
        allowedExtensions = props.allowedExtensions.toSet(),
        maxFileSize = props.maxFileSize,
        maxFiles = props.maxFilesPerResponse,
    )

    /**
     * [OutputFileStore] bean — MinIO-backed file persistence.
     * Only created when MinIO is enabled.
     */
    @Bean
    @ConditionalOnProperty(prefix = "harness.minio", name = ["enabled"], havingValue = "true")
    fun outputFileStore(
        minioClient: MinioClient,
        minioConfig: MinioConfig,
        outputProps: OutputDetectionProperties,
    ): OutputFileStore = MinioOutputFileStore(
        minioClient = minioClient,
        bucketName = minioConfig.outputBucket,
        adminBaseUrl = outputProps.adminBaseUrl,
    )

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
            network = sandboxProps.network,
            cliPluginsEnabled = sandboxProps.cliPluginsEnabled,
            pluginImage = sandboxProps.pluginImage,
            pluginAdminUrl = sandboxProps.pluginAdminUrl,
            pluginInternalSecret = sandboxProps.pluginInternalSecret,
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
    @Bean(destroyMethod = "shutdown")
    fun harnessAgentLauncher(
        chatModelConfigAdaptor: ChatModelConfigAdaptor,
        mcpConfigAdaptor: McpConfigAdaptor,
        skillAdaptor: SkillAdaptor,
        tokenStatAdaptor: TokenStatAdaptor,
        sessionConfig: SessionConfig,
        processLogAdaptor: ProcessLogAdaptor,
        toolCallLogAdaptorProvider: ObjectProvider<ToolCallLogAdaptor>,
        mcpConfigDecryptorProvider: ObjectProvider<McpConfigDecryptor>,
        toolConfigAdaptorProvider: ObjectProvider<ToolConfigAdaptor>,
        toolRegistryProvider: ObjectProvider<ToolRegistry>,
        planNoteAdaptor: PlanNoteAdaptor,
        harnessConfig: HarnessConfig,
        @Value("\${local.tmp-dir:/tmp/harnax-agent}") tmpDir: String,
        minioConfig: MinioConfig?,
        outputFileDetectorProvider: ObjectProvider<OutputFileDetector>,
        outputFileStoreProvider: ObjectProvider<OutputFileStore>,
    ): HarnessAgentLauncher {
        val toolCallLogAdaptor = toolCallLogAdaptorProvider.ifAvailable
            ?: ToolCallLogAdaptor { /* no-op */ }
        // Never null: a missing bean used to leave headers and stdio env params silently empty.
        // agent-service has no AES key — admin delivers those fields decrypted — so the fallback
        // parses plain text rather than decrypting. A real decryptor elsewhere still wins.
        val mcpConfigDecryptor = mcpConfigDecryptorProvider.ifAvailable
            ?: PlaintextMcpConfigDecryptor()
        val toolConfigAdaptor = toolConfigAdaptorProvider.ifAvailable
        val toolRegistry = toolRegistryProvider.ifAvailable
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
            mcpConfigDecryptor = mcpConfigDecryptor,
            toolConfigAdaptor = toolConfigAdaptor,
            toolRegistry = toolRegistry,
            outputFileDetector = outputFileDetectorProvider.ifAvailable,
            outputFileStore = outputFileStoreProvider.ifAvailable,
        )
    }
}
