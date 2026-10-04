package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.SysUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * The memory sweep an admin's user deletion runs.
 *
 * Two things are asserted here and both are about reach: the prefixes addressed are the ones this owner's
 * own memberships build, and a store that cannot delete is thrown at the caller rather than answered with a
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
        `when`(gateway.deleteUser(listOf(4L), "7")).thenReturn(5)

        assertEquals(5, cleaner().deleteForUser(user(7L, 4L), emptyList()))

        verify(gateway).deleteUser(listOf(4L), "7")
    }

    /**
     * The bucket is keyed on the tenant of the agent that was talked to, so an account that is a member of
     * several tenants has memory spread over all of them. Sweeping only `sys_user.tenant_id` leaves the rest
     * in the store while the admin is told the account was cleaned.
     */
    @Test
    fun `every tenant the user was a member of is swept, not only the home tenant`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(listOf(4L, 5L, 6L), "7")).thenReturn(3)

        assertEquals(3, cleaner().deleteForUser(user(7L, 4L), listOf(5L, 6L)))

        verify(gateway).deleteUser(listOf(4L, 5L, 6L), "7")
    }

    /** The home tenant is usually also a membership row; naming a tenant twice must not sweep it twice. */
    @Test
    fun `a tenant that is both the home and a membership is swept once`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(listOf(4L), "7")).thenReturn(1)

        assertEquals(1, cleaner().deleteForUser(user(7L, 4L), listOf(4L, 4L)))

        verify(gateway).deleteUser(listOf(4L), "7")
    }

    /**
     * Without a tenant there is no correct prefix, and a guessed one is somebody else's workspace: the sweep
     * refuses to address anything at all.
     */
    @Test
    fun `a user with no tenant and no membership is not swept`() {
        `when`(gateway.isAvailable()).thenReturn(true)

        assertEquals(0, cleaner().deleteForUser(user(7L, null), emptyList()))

        verify(gateway, never()).deleteUser(anyList(), anyString())
    }

    /** A row whose home tenant is unset still has its memberships swept — the leak does not depend on the column. */
    @Test
    fun `a user with no home tenant still has its memberships swept`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(listOf(5L), "7")).thenReturn(2)

        assertEquals(2, cleaner().deleteForUser(user(7L, null), listOf(5L)))

        verify(gateway).deleteUser(listOf(5L), "7")
    }

    @Test
    fun `a user whose tenant is zero is not swept`() {
        `when`(gateway.isAvailable()).thenReturn(true)

        assertEquals(0, cleaner().deleteForUser(user(7L, 0L), emptyList()))

        verify(gateway, never()).deleteUser(anyList(), anyString())
    }

    /** A deployment that never turned MinIO on has no memory to remove, and the account delete must succeed. */
    @Test
    fun `no store on this instance skips the sweep instead of failing the deletion`() {
        `when`(gateway.isAvailable()).thenReturn(false)

        assertEquals(0, cleaner().deleteForUser(user(7L, 4L), listOf(5L)))

        verify(gateway, never()).deleteUser(anyList(), anyString())
    }

    @Test
    fun `a sweep that cannot delete is thrown at the caller`() {
        `when`(gateway.isAvailable()).thenReturn(true)
        `when`(gateway.deleteUser(listOf(4L), "7")).thenThrow(BizException(503, "Memory could not be fully deleted"))

        val failure = assertThrows(BizException::class.java) {
            cleaner().deleteForUser(user(7L, 4L), emptyList())
        }

        assertEquals(503, failure.code)
    }
}
