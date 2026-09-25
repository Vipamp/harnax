package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.ErrorBundle
import com.agnetix.harnax.admin.service.AgentRuntimeClient
import com.agnetix.harnax.common.dto.ResultVo
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.Locale
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The one place the "release the runtime first, and treat a refusal as a failed delete" rule lives.
 *
 * Three deletes lean on it — the admin session page, the mobile session, and a channel taking its
 * `chn-{uuid}` conversation with it — so what is pinned here is what those three share rather than a copy
 * of each call site. The end-to-end halves (a row that survives a refusal) are pinned by
 * `SessionRuntimeCleanupIT` and `ChannelSessionCascadeIT`.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionRuntimeReleaserTest {

    @Mock
    private lateinit var agentRuntimeClient: AgentRuntimeClient

    /**
     * The real bundle, loaded the way `I18nConfig.errorMessageSource` loads it.
     *
     * A stubbed `MessageUtil` would let the refusal become a sentence nobody ships: `getMessage` answers an
     * unknown code with the code itself, and a test that mocked it away would still pass.
     */
    private val messageUtil = ErrorBundle.messageUtil()

    private fun releaser(): SessionRuntimeReleaser = SessionRuntimeReleaser(agentRuntimeClient, messageUtil)

    @Test
    fun `release names the session by the id the runtime keys it under`() {
        `when`(agentRuntimeClient.clearSession(SESSION_ID)).thenReturn(ResultVo.success())

        assertDoesNotThrow { releaser().release(SESSION_ID) }

        verify(agentRuntimeClient).clearSession(SESSION_ID)
    }

    @Test
    fun `a refusal becomes an exception so the caller cannot overlook it`() {
        `when`(agentRuntimeClient.clearSession(SESSION_ID))
            .thenReturn(ResultVo.error(500, RUNTIME_REASON))

        val exception = assertThrows<BizException> { releaser().release(SESSION_ID) }

        // Returning a flag instead would be the shape every one of the three callers has to remember to
        // check, and a forgotten check is precisely the half-done delete this rule exists to prevent.
        assertTrue(
            exception.message!!.contains(RUNTIME_REASON),
            "the runtime's own sentence is what the operator can act on, got: ${exception.message}",
        )
        assertNotEquals(REFUSAL_KEY, exception.message, "the refusal must be the bundle's text, not the key")
    }

    /**
     * Every shipped bundle carries the refusal.
     *
     * A missing key does not fail: `useCodeAsDefaultMessage` turns it into the bare key, so the English and
     * Simplified-Chinese paths would both read `error.session.runtime.release` and the callers' own
     * assertions would still pass. This is the only place the three files are checked together.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource("default,", "en,en", "zh-CN,zh-CN")
    fun `the refusal is worded in every bundle the application ships`(
        label: String,
        tag: String?,
    ) {
        // A blank tag is the request that matches no `messages_error_xx` file, which is where
        // `useCodeAsDefaultMessage` starts answering from: the unsuffixed bundle.
        val message = ErrorBundle
            .messageSource()
            .getMessage(REFUSAL_KEY, arrayOf(RUNTIME_REASON), Locale.forLanguageTag(tag.orEmpty()))

        assertNotEquals(REFUSAL_KEY, message, "$label bundle is missing the key")
        assertTrue(message.contains(RUNTIME_REASON), "$label bundle drops the runtime's reason: $message")
    }

    @Test
    fun `an unreachable runtime is a refusal too`() {
        // `AgentRuntimeClientImpl` folds a connection failure into a non-200 rather than throwing, so this
        // is the case that decides whether a router outage reads as "deleted" or as "try again".
        `when`(agentRuntimeClient.clearSession(SESSION_ID))
            .thenReturn(ResultVo.error(500, "Router request failed: connection refused"))

        val exception = assertThrows<BizException> { releaser().release(SESSION_ID) }

        assertTrue(
            exception.message!!.contains("connection refused"),
            "an outage has to say so, got: ${exception.message}",
        )
    }

    private companion object {
        const val SESSION_ID = "chn-11111111-2222-3333-4444-555555555555"
        const val RUNTIME_REASON = "sandbox container is busy"
        const val REFUSAL_KEY = "error.session.runtime.release"
    }
}
