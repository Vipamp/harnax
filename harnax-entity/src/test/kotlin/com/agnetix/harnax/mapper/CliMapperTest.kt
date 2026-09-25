package com.agnetix.harnax.mapper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CliMapper locking-read tests.
 *
 * The registrar takes the shipped skill's status from [CliMapper.selectByNameForUpdate], and the page's
 * toggle writes the same column through [CliMapper.updateStatus]. That convergence is a database property,
 * not a Kotlin one: it exists only while the read actually locks the row. A mocked mapper cannot see this,
 * so the assertions here run against MySQL and judge the statement, not the code around it.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class CliMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var cliMapper: CliMapper

    @Autowired
    private lateinit var dataSource: DataSource

    @Nested
    @DisplayName("Locked lookup")
    inner class LockedLookup {

        @Test
        fun `the locked read answers the row the plain read answers`() {
            seedPackage("demo-lock-parity")

            val locked = cliMapper.selectByNameForUpdate("demo-lock-parity")
            val plain = cliMapper.selectByName("demo-lock-parity")

            assertNotNull(locked)
            assertNotNull(plain)
            assertEquals(plain.id, locked.id)
            assertEquals(plain.name, locked.name)
            assertEquals(plain.status, locked.status)
        }

        @Test
        fun `the locked read answers null for a package that is not registered yet`() {
            assertNull(cliMapper.selectByNameForUpdate("never-registered"))
        }
    }

    @Nested
    @DisplayName("Row lock")
    inner class RowLock {

        /**
         * The whole point of the locked read: while the registrar's transaction holds it, the toggle's
         * `updateStatus` cannot land, so it runs afterwards and sees a skill that already carries the
         * status it wrote.
         */
        @Test
        fun `the locked read holds the row against the status update`() {
            val id = seedPackage("demo-lock-holder")

            assertNotNull(cliMapper.selectByNameForUpdate("demo-lock-holder"))
            val refused = tryUpdateStatus(id, 0)

            assertNotNull(refused, "the update was not blocked — SELECT ... FOR UPDATE locks nothing")
            assertLockWaitTimeout(refused)
        }

        /**
         * The control for the test above: without the locked read, the same update from a second
         * connection succeeds in milliseconds, so a failure there would mean the harness is broken.
         */
        @Test
        fun `the status update lands when no locked read precedes it`() {
            val id = seedPackage("demo-lock-control")

            val refused = tryUpdateStatus(id, 0)

            assertNull(refused, "the update failed on its own: ${refused?.message}")
            assertEquals(0, cliMapper.selectById(id)?.status)
        }
    }

    /**
     * Inserts one package row and commits it on a connection of its own.
     *
     * The test method's transaction is rolled back, so a row written through the mapper would be invisible
     * to the second connection below — and it would carry the insert's own row lock, which is exactly what
     * [RowLock] has to attribute to the locking read alone.
     */
    private fun seedPackage(name: String): Long {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.prepareStatement(
                "INSERT INTO cli (name, description, version, package_digest, package_object, status, active) " +
                    "VALUES (?, '', '1.0.0', ?, ?, 1, 1)",
            ).use { ps ->
                ps.setString(1, name)
                ps.setString(2, "0".repeat(64))
                ps.setString(3, "$name/0.zip")
                ps.executeUpdate()
            }
            conn.prepareStatement("SELECT id FROM cli WHERE name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    /**
     * Runs the toggle's own update from a second connection, giving up after one second of lock wait.
     *
     * @return the SQLException the wait produced, or null when the update ran through.
     */
    private fun tryUpdateStatus(
        id: Long,
        status: Int,
    ): SQLException? = try {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.applyLockWait(1)
            conn.prepareStatement("UPDATE cli SET status = ? WHERE id = ? AND active = 1").use { ps ->
                ps.setInt(1, status)
                ps.setLong(2, id)
                assertEquals(1, ps.executeUpdate())
            }
        }
        null
    } catch (e: SQLException) {
        e
    }

    private fun Connection.applyLockWait(seconds: Int) {
        createStatement().use { it.execute("SET SESSION innodb_lock_wait_timeout = $seconds") }
    }

    private fun assertLockWaitTimeout(e: SQLException) {
        // 1205 = ER_LOCK_WAIT_TIMEOUT. Anything else (e.g. a syntax or permission error) would let a
        // broken statement pass as "the lock held".
        assertTrue(e.errorCode == 1205, "expected lock-wait timeout (1205) but got errorCode=${e.errorCode}: ${e.message}")
    }
}
