package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Model and provider tenancy (A.4): `tenant_id` has existed on both tables since V1, but no insert ever
 * wrote it, so every row landed in the DDL default and every read filtered on the caller instead.
 *
 * The rule being proven is the pair the list query uses: a row belongs to one tenant, and `is_public`
 * says whether the rest of the platform may *use* it. Publication never moves ownership — writing,
 * deleting and spending the stored API key stay with the tenant that holds the row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ModelTenantIsolationIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)

    /** A tenant that exists nowhere else, so these rows cannot collide with another class's. */
    private val otherTenant = 940_002L

    /** Reads as absent wherever it is used, so a refusal can be compared against a row nobody holds. */
    private val noSuchId = 999_999_999L

    private val ownPrivateProvider = "it_t1_private_provider_$suffix"
    private val sharedProvider = "it_t1_shared_provider_$suffix"
    private val otherProvider = "it_t2_provider_$suffix"
    private val otherPrivateModel = "it_t2_private_model_$suffix"

    private var ownPrivateProviderId: Long = -1
    private var sharedProviderId: Long = -1
    private var otherProviderId: Long = -1
    private var otherPrivateModelId: Long = -1

    /** The same name held by the other tenant, to prove uniqueness stopped being global. */
    private var clashProviderId: Long = -1

    /** Records on a name-filtered page carrying exactly this name, as the given tenant sees them. */
    private fun rowsNamed(
        basePath: String,
        name: String,
        tenantId: Long? = null,
    ): List<JsonNode> {
        val data = assertOk(parseBody(exchange(HttpMethod.GET, "$basePath?pageNum=1&pageSize=50&name=$name", tenantId = tenantId)))
        val records = data["records"]
        return if (records == null || !records.isArray) emptyList() else records.filter { it["name"]?.asText() == name }
    }

    private fun rowId(
        basePath: String,
        name: String,
        tenantId: Long?,
    ): Long = rowsNamed(basePath, name, tenantId)
        .firstOrNull()
        ?.get("id")
        ?.asLong()
        ?: error("no row named $name visible to tenant ${tenantId ?: "default"}")

    /** The message a refusal carries, compared rather than matched so the locale stays irrelevant. */
    private fun messageOf(node: JsonNode): String = node["message"].asText()

    /** The envelope a row nobody holds answers with, which is what an invisible row has to look like. */
    private fun answersAsAbsent(node: JsonNode): Boolean = node["data"] == null || node["data"].isNull

    /** A text field carrying nothing: absent, null and empty all read as "this write never landed". */
    private fun carriesNothing(
        node: JsonNode,
        field: String,
    ): Boolean {
        val value = node[field]
        return value == null || value.isNull || value.asText().isEmpty()
    }

    @Test
    @Order(1)
    fun `a provider created under a tenant stores that tenant on the row`() {
        // The old insert never listed the column, so this is the sentence the whole fix rests on:
        // a row created by tenant N answers as tenant N's, not as the DDL default.
        assertOk(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/model-providers",
                    mapOf("type" to "it_t2_$suffix", "name" to otherProvider, "isPublic" to 1),
                    tenantId = otherTenant,
                ),
            ),
        )
        otherProviderId = rowId("/api/admin/model-providers/page", otherProvider, otherTenant)

        assertEquals(
            otherTenant,
            jdbc.queryForObject("SELECT tenant_id FROM model_provider WHERE id = ?", Long::class.java, otherProviderId)!!,
        )
    }

    @Test
    @Order(2)
    fun `a private row of one tenant is absent from the other list and unreadable by id`() {
        assertOk(postJson("/api/admin/model-providers", mapOf("type" to "it_t1_private_$suffix", "name" to ownPrivateProvider, "isPublic" to 0)))
        ownPrivateProviderId = rowId("/api/admin/model-providers/page", ownPrivateProvider, null)

        assertTrue(rowsNamed("/api/admin/model-providers/page", ownPrivateProvider, otherTenant).isEmpty(), "a private row must stay out of another tenant's list")

        val hidden = parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$ownPrivateProviderId", tenantId = otherTenant))
        assertEquals(200, hidden["code"].asInt(), "an invisible row reads as absent, not as an error")
        assertTrue(answersAsAbsent(hidden), "another tenant's private provider must not be readable by id")
        // Same shape as an id nobody holds: probing the id space cannot enumerate whose rows exist.
        assertTrue(answersAsAbsent(getJson("/api/admin/model-providers/$noSuchId")))

        assertEquals(ownPrivateProvider, assertOk(getJson("/api/admin/model-providers/$ownPrivateProviderId"))["name"].asText())
    }

    @Test
    @Order(3)
    fun `a public row of one tenant is listed for the other yet stays unwritable`() {
        assertOk(postJson("/api/admin/model-providers", mapOf("type" to "it_t1_shared_$suffix", "name" to sharedProvider, "isPublic" to 1)))
        sharedProviderId = rowId("/api/admin/model-providers/page", sharedProvider, null)

        assertEquals(1, rowsNamed("/api/admin/model-providers/page", sharedProvider, otherTenant).size, "a public row is listed for the rest of the platform")
        assertEquals(sharedProvider, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$sharedProviderId", tenantId = otherTenant)))["name"].asText())

        // Visible is not editable: the stored API key is the owning tenant's credential.
        val write = mapOf("description" to "hijacked")
        val refused = parseBody(exchange(HttpMethod.PUT, "/api/admin/model-providers/update/$sharedProviderId", write, tenantId = otherTenant))
        assertErr(refused)
        val refusedAsMissing = parseBody(exchange(HttpMethod.PUT, "/api/admin/model-providers/update/$noSuchId", write, tenantId = otherTenant))
        assertErr(refusedAsMissing)
        assertEquals(messageOf(refusedAsMissing), messageOf(refused), "the refusal should not reveal that the row exists at all")
        assertTrue(carriesNothing(assertOk(getJson("/api/admin/model-providers/$sharedProviderId")), "description"), "a refused write must not have landed")

        assertErr(parseBody(exchange(HttpMethod.DELETE, "/api/admin/model-providers/$sharedProviderId", tenantId = otherTenant)))
        assertEquals(1, rowsNamed("/api/admin/model-providers/page", sharedProvider, null).size, "a refused delete must not have taken the row")

        // Spending the provider's key and reading its counts stay with the owner too.
        assertErr(parseBody(exchange(HttpMethod.POST, "/api/admin/model-providers/$sharedProviderId/test", tenantId = otherTenant)))
        assertErr(parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$sharedProviderId/stats", tenantId = otherTenant)))

        // The same body from the owning tenant goes through, so the refusals above came from the tenant
        // and not from anything about the request.
        assertOk(putJson("/api/admin/model-providers/update/$sharedProviderId", write))
        assertEquals("hijacked", assertOk(getJson("/api/admin/model-providers/$sharedProviderId"))["description"].asText())
    }

    @Test
    @Order(4)
    fun `another tenant may take a name the first one already used`() {
        // Uniqueness used to be global, which let one tenant block another from a name it never owned.
        assertOk(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/model-providers",
                    mapOf("type" to "it_name_clash_$suffix", "name" to ownPrivateProvider, "isPublic" to 0),
                    tenantId = otherTenant,
                ),
            ),
        )
        clashProviderId = rowId("/api/admin/model-providers/page", ownPrivateProvider, otherTenant)
        assertNotEquals(ownPrivateProviderId, clashProviderId, "the two rows must be different rows, not one row seen twice")
        assertEquals(
            otherTenant,
            jdbc.queryForObject("SELECT tenant_id FROM model_provider WHERE id = ?", Long::class.java, clashProviderId)!!,
        )

        // Each tenant lists exactly its own row: neither displaced, neither merged.
        assertEquals(1, rowsNamed("/api/admin/model-providers/page", ownPrivateProvider, null).size)
        assertEquals(1, rowsNamed("/api/admin/model-providers/page", ownPrivateProvider, otherTenant).size)
    }

    @Test
    @Order(5)
    fun `a model is stored under its creator tenant and cannot be built on a private provider of another tenant`() {
        val foreignPrivate = rowId("/api/admin/model-providers/page", ownPrivateProvider, null)
        assertErr(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/models",
                    mapOf("name" to "it_t2_on_t1_private_$suffix", "modelName" to "it-t2-on-t1-private-$suffix", "providerId" to foreignPrivate, "modelType" to "chat"),
                    tenantId = otherTenant,
                ),
            ),
        )

        assertOk(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/models",
                    mapOf("name" to otherPrivateModel, "modelName" to "it-t2-private-$suffix", "providerId" to otherProviderId, "modelType" to "chat", "isPublic" to 0),
                    tenantId = otherTenant,
                ),
            ),
        )
        otherPrivateModelId = rowId("/api/admin/models/page", otherPrivateModel, otherTenant)
        assertEquals(otherTenant, jdbc.queryForObject("SELECT tenant_id FROM model WHERE id = ?", Long::class.java, otherPrivateModelId)!!)
    }

    @Test
    @Order(6)
    fun `a private model of one tenant stays out of the other list and detail`() {
        assertTrue(rowsNamed("/api/admin/models/page", otherPrivateModel, null).isEmpty(), "a private model must stay out of another tenant's list")

        val hidden = getJson("/api/admin/models/$otherPrivateModelId")
        assertEquals(200, hidden["code"].asInt())
        assertTrue(answersAsAbsent(hidden), "another tenant's private model must not be readable by id")
        assertTrue(answersAsAbsent(getJson("/api/admin/models/$noSuchId")))

        assertEquals(otherPrivateModel, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/models/$otherPrivateModelId", tenantId = otherTenant)))["name"].asText())
    }

    @Test
    @Order(7)
    fun `a private model of one tenant cannot be edited or deleted by the other`() {
        val write = mapOf("description" to "hijacked")
        val refused = putJson("/api/admin/models/update/$otherPrivateModelId", write)
        assertErr(refused)
        assertEquals(messageOf(putJson("/api/admin/models/update/$noSuchId", write)), messageOf(refused), "the refusal should not reveal that the row exists at all")
        val ownerView = assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/models/$otherPrivateModelId", tenantId = otherTenant)))
        assertTrue(carriesNothing(ownerView, "description"), "a refused write must not have landed")

        assertErr(deleteJson("/api/admin/models/$otherPrivateModelId"))
        assertEquals(1, rowsNamed("/api/admin/models/page", otherPrivateModel, otherTenant).size, "a refused delete must leave the row where it was")
    }

    @Test
    @Order(8)
    fun `cleanup removes what this class created`() {
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/models/$otherPrivateModelId", tenantId = otherTenant)))
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/model-providers/$otherProviderId", tenantId = otherTenant)))
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/model-providers/$clashProviderId", tenantId = otherTenant)))
        assertOk(deleteJson("/api/admin/model-providers/$ownPrivateProviderId"))
        assertOk(deleteJson("/api/admin/model-providers/$sharedProviderId"))
    }
}
