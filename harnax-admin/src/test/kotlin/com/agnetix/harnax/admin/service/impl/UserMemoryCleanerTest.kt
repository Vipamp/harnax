package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.SysUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * The memory sweep an admin's user deletion runs.
 *
 * Two things are asserted here and both are about reach: the prefix addressed is the one the row's own
 * tenant and id build, and a store that cannot delete is thrown at the caller rather than answered with a
 * quiet zero — the caller is inside the user-delete transaction and has to be able to roll back.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserMemoryCleaner - the delete-user memory sweep")
class UserMemoryCleanerTest {

    @Mock
    private lateinit var gateway: MemoryStoreGateway

    private fun cleaner() = UserMemoryCleaner(gateway)

    private fun user(
        id: Long,
        tenantId: Long?,
    ): SysUser = SysUser().apply {
        this.id = id
        username = "linqing"
        this.tenantId = tenantId
    }

    @Test
    fun `a tenant-owning user is swept by its own tenant and id`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(4L, "7")).thenReturn(5)

        assertEquals(5, cleaner().deleteForUser(user(7L, 4L)))

        verify(gateway).deleteUser(4L, "7")
    }

    /**
     * Without a tenant there is no correct prefix, and a guessed one is somebody else's workspace: the sweep
     * refuses to address anything at all.
     */
    @Test
    fun `a user with no tenant is not swept`() {
        `when`(gateway.isAvailable()).thenReturn(true)

        assertEquals(0, cleaner().deleteForUser(user(7L, null)))

        verify(gateway, never()).deleteUser(anyLong(), anyString())
    }

    @Test
    fun `a user whose tenant is zero is not swept`() {
        `when`(gateway.isAvailable()).thenReturn(true)

        assertEquals(0, cleaner().deleteForUser(user(7L, 0L)))

        verify(gateway, never()).deleteUser(anyLong(), anyString())
    }

    /** A deployment that never turned MinIO on has no memory to remove, and the account delete must succeed. */
    @Test
    fun `no store on this instance skips the sweep instead of failing the deletion`() {
        `when`(gateway.isAvailable()).thenReturn(false)

        assertEquals(0, cleaner().deleteForUser(user(7L, 4L)))

        verify(gateway, never()).deleteUser(anyLong(), anyString())
    }

    /** The failure is the answer: swallowing it would report a cleaned account that still has a bucket. */
    @Test
    fun `a store failure is propagated to the caller`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(4L, "7")).thenThrow(BizException(503, "Memory could not be fully deleted"))

        val failure = assertThrows(BizException::class.java) { cleaner().deleteForUser(user(7L, 4L)) }

        assertEquals(503, failure.code)
    }
}
