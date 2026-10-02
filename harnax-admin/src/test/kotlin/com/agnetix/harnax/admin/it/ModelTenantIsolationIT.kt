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
 * Model and provider tenancy (A.4): `tenant_id` has existed on both tables since the schema baseline,
 * but no insert ever wrote it, so every row landed in the DDL default and every read filtered on the
 * caller instead.
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
    private val sharedModel = "it_t1_shared_model_$suffix"
    private val sharedModelTechnical = "it-t1-shared-$suffix"
    private val matePrivateModel = "it_t1_mate_private_model_$suffix"

    private var ownPrivateProviderId: Long = -1
    private var sharedProviderId: Long = -1
    private var otherProviderId: Long = -1
    private var otherPrivateModelId: Long = -1
    private var sharedModelId: Long = -1
    private var matePrivateModelId: Long = -1

    /**
     * A second account inside tenant 1, which is the half of the rule the tenant header cannot show:
     * sharing is for the rest of *this* tenant, so a colleague must reach a shared row and no further.
     */
    private val mateToken: String by lazy { jwtUtil.generateToken(99997L, "it_mate_$suffix", 1L, 0) }

    /** The same name held by the other tenant, to prove uniqueness stopped being global. */
    private var clashProviderId: Long = -1

    /**
     * Records on a name-filtered page carrying exactly this name, as [token] sees them from within
     * [tenantId]. Both default to the admin caller, so an existing assertion need not spell them out.
     */
    private fun rowsNamed(
        basePath: String,
        name: String,
        tenantId: Long? = null,
        token: String? = null,
    ): List<JsonNode> {
        val data = assertOk(
            parseBody(
                exchange(
                    HttpMethod.GET,
                    "$basePath?pageNum=1&pageSize=50&name=$name",
                    tenantId = tenantId,
                    token = token ?: adminToken(),
                ),
            ),
        )
        val records = data["records"]
        return if (records == null || !records.isArray) emptyList() else records.filter { it["name"]?.asText() == name }
    }

    private fun rowId(
        basePath: String,
        name: String,
        tenantId: Long? = null,
        token: String? = null,
    ): Long = rowsNamed(basePath, name, tenantId, token)
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

        val hiddenResponse = exchange(HttpMethod.GET, "/api/admin/model-providers/$ownPrivateProviderId", tenantId = otherTenant)
        // Same shape as an id nobody holds: probing the id space cannot enumerate whose rows exist.
        val absentResponse = exchange(HttpMethod.GET, "/api/admin/model-providers/$noSuchId")
        val hidden = parseBody(hiddenResponse)
        val absent = parseBody(absentResponse)
        // The miss travels in the envelope, so neither answer may turn into an HTTP error status.
        assertEquals(200, hiddenResponse.statusCode.value(), "an invisible row must answer HTTP 200 with an envelope 404")
        assertEquals(200, absentResponse.statusCode.value(), "a missing row must answer HTTP 200 with an envelope 404")
        assertEquals(absent["code"].asInt(), hidden["code"].asInt(), "an invisible row must not read differently from a missing one")
        assertEquals(messageOf(absent), messageOf(hidden), "the refusal must not reveal that the row exists at all")
        // getMessage echoes the key itself when the bundle lacks it, which would leave both sides equal and
        // make the line above pass on an untranslated response — so pin that the text really resolved.
        assertNotEquals("error.model.provider.notfound", messageOf(absent), "the refusal must be the bundle's text, not the key")
        assertEquals(404, hidden["code"].asInt(), "a read that misses is a 404, not an empty success")
        assertTrue(answersAsAbsent(hidden), "another tenant's private provider must not be readable by id")
        assertTrue(answersAsAbsent(absent))

        assertEquals(ownPrivateProvider, assertOk(getJson("/api/admin/model-providers/$ownPrivateProviderId"))["name"].asText())
    }

    @Test
    @Order(3)
    fun `a public row stays inside its tenant and unreadable from the other`() {
        assertOk(postJson("/api/admin/model-providers", mapOf("type" to "it_t1_shared_$suffix", "name" to sharedProvider, "isPublic" to 1)))
        sharedProviderId = rowId("/api/admin/model-providers/page", sharedProvider, null)

        // Publication is a within-tenant act: the other tenant's page never carries the row at all.
        assertTrue(rowsNamed("/api/admin/model-providers/page", sharedProvider, otherTenant).isEmpty(), "a public row must stay out of another tenant's list")

        // And by id it answers exactly like a row nobody holds, so the id space is not askable.
        val hidden = parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$sharedProviderId", tenantId = otherTenant))
        val absent = parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$noSuchId", tenantId = otherTenant))
        assertEquals(absent["code"].asInt(), hidden["code"].asInt(), "an invisible row must not read differently from a missing one")
        assertEquals(messageOf(absent), messageOf(hidden), "the refusal must not reveal that the row exists at all")
        assertTrue(answersAsAbsent(hidden), "another tenant's public provider must not be readable by id")

        // Visible is not editable even inside its own tenant, and across one it is not even visible: the
        // stored API key is the owning tenant's credential.
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

        val hiddenResponse = exchange(HttpMethod.GET, "/api/admin/models/$otherPrivateModelId")
        val absentResponse = exchange(HttpMethod.GET, "/api/admin/models/$noSuchId")
        val hidden = parseBody(hiddenResponse)
        val absent = parseBody(absentResponse)
        assertEquals(200, hiddenResponse.statusCode.value(), "an invisible model must answer HTTP 200 with an envelope 404")
        assertEquals(200, absentResponse.statusCode.value(), "a missing model must answer HTTP 200 with an envelope 404")
        assertEquals(absent["code"].asInt(), hidden["code"].asInt(), "an invisible model must not read differently from a missing one")
        assertEquals(messageOf(absent), messageOf(hidden), "the refusal must not reveal that the row exists at all")
        assertNotEquals("error.model.notfound", messageOf(absent), "the refusal must be the bundle's text, not the key")
        assertEquals(404, hidden["code"].asInt(), "a read that misses is a 404, not an empty success")
        assertTrue(answersAsAbsent(hidden), "another tenant's private model must not be readable by id")
        assertTrue(answersAsAbsent(absent))

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
    fun `a model is bindable only as far as it is visible, by the write and by the read alike`() {
        // The pair the rule has to hold on both sides of: a reference this tenant may not see is
        // refused going in, and stays silent coming out.
        val model = assertOk(getJson("/api/admin/models/${ensureAgentModelId()}"))
        val ownName = "it_agent_own_model_$suffix"
        assertOk(postJson("/api/admin/agents", agentCreateBody(ownName)))
        val ownId = agentIdNamed(ownName)
        assertEquals(
            model["modelName"].asText(),
            assertOk(getJson("/api/admin/agents/$ownId"))["modelName"].asText(),
            "a model this tenant holds must still be named - the rule is visibility, not a blank card",
        )

        val foreignName = "it_agent_foreign_model_$suffix"
        val refusedCreate = assertErr(postJson("/api/admin/agents", agentCreateBody(foreignName) + mapOf("modelId" to otherPrivateModelId)))
        assertTrue(messageOf(refusedCreate).contains(otherPrivateModelId.toString()), "the refusal should name the id it refused: $refusedCreate")
        assertTrue(rowsNamed("/api/admin/agents/page", foreignName, null).isEmpty(), "a refused create must not have left a row behind")

        // The same refusal on the rename, without disturbing the model already on the row.
        assertErr(putJson("/api/admin/agents/update/$ownId", mapOf("modelId" to otherPrivateModelId)))
        assertEquals(
            model["modelName"].asText(),
            assertOk(getJson("/api/admin/agents/$ownId"))["modelName"].asText(),
            "a refused write must not have moved the binding",
        )

        // What a reference that got there without asking looks like: a row bound before the write
        // check existed. The id stays on the response because the operator saved it; the name and
        // price do not.
        val legacyName = "it_agent_legacy_model_$suffix"
        assertOk(postJson("/api/admin/agents", agentCreateBody(legacyName)))
        val legacyId = agentIdNamed(legacyName)
        assertEquals(1, jdbc.update("UPDATE agent SET model_id = ? WHERE id = ?", otherPrivateModelId, legacyId))
        val legacy = assertOk(getJson("/api/admin/agents/$legacyId"))
        assertEquals(otherPrivateModelId, legacy["modelId"].asLong(), "the reference the operator saved stays on the row")
        assertTrue(carriesNothing(legacy, "modelName"), "another tenant's private model must not be named through an agent binding")
        assertTrue(carriesNothing(legacy, "modelPrice"), "nor priced out")

        assertOk(deleteJson("/api/admin/agents/$ownId"))
        assertOk(deleteJson("/api/admin/agents/$legacyId"))
    }

    /** The one live agent carried by this name, as the calling tenant sees it. */
    private fun agentIdNamed(name: String): Long = findInPage("/api/admin/agents/page", "name=$name") {
        it["name"]?.asText() == name
    }?.get("id")?.asLong() ?: error("no agent named $name visible here")

    @Test
    @Order(9)
    fun `a public row is visible and usable by every member of its own tenant`() {
        // Two rows in tenant 1 that differ only in the sharing flag, plus one a colleague keeps private.
        assertOk(
            postJson(
                "/api/admin/models",
                mapOf("name" to sharedModel, "modelName" to "it-t1-shared-$suffix", "providerId" to sharedProviderId, "modelType" to "chat", "isPublic" to 1),
            ),
        )
        sharedModelId = rowId("/api/admin/models/page", sharedModel, null)
        assertOk(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/models",
                    mapOf("name" to matePrivateModel, "modelName" to "it-t1-mate-private-$suffix", "providerId" to sharedProviderId, "modelType" to "chat", "isPublic" to 0),
                    mateToken,
                ),
            ),
        )
        matePrivateModelId = rowId("/api/admin/models/page", matePrivateModel, token = mateToken)

        // The colleague lists the shared provider and the shared model, and reads both by id: within the
        // tenant, public means the rest of the tenant may use it.
        assertEquals(1, rowsNamed("/api/admin/model-providers/page", sharedProvider, token = mateToken).size, "a shared provider must reach a colleague of its tenant")
        assertEquals(sharedProvider, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/model-providers/$sharedProviderId", token = mateToken)))["name"].asText())
        assertEquals(1, rowsNamed("/api/admin/models/page", sharedModel, token = mateToken).size, "a shared model must reach a colleague of its tenant")
        assertEquals(sharedModel, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/models/$sharedModelId", token = mateToken)))["name"].asText())

        // Usable, not merely named: the colleague's own agent resolves the shared model through the read.
        val mateAgent = "it_mate_agent_$suffix"
        assertOk(
            parseBody(
                exchange(
                    HttpMethod.POST,
                    "/api/admin/agents",
                    agentCreateBody(mateAgent) + mapOf("modelId" to sharedModelId),
                    mateToken,
                ),
            ),
        )
        val mateAgentRow = assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/agents/page?pageNum=1&pageSize=50&name=$mateAgent", token = mateToken)))
        val mateAgentId = mateAgentRow["records"].firstOrNull { it["name"]?.asText() == mateAgent }?.get("id")?.asLong()
            ?: error("the colleague's own agent vanished from its own page: $mateAgentRow")
        assertEquals(
            sharedModelTechnical,
            assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/agents/$mateAgentId", token = mateToken)))["modelName"].asText(),
            "a shared model must be usable by the colleague, not just listed",
        )
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/agents/$mateAgentId", token = mateToken)))

        // The colleague's private row stops there: absent from the tenant's other members either way.
        assertTrue(rowsNamed("/api/admin/models/page", matePrivateModel).isEmpty(), "a private model must stay out of a colleague's list")
        val hiddenFromAdmin = parseBody(exchange(HttpMethod.GET, "/api/admin/models/$matePrivateModelId"))
        val absent = parseBody(exchange(HttpMethod.GET, "/api/admin/models/$noSuchId"))
        assertEquals(absent["code"].asInt(), hiddenFromAdmin["code"].asInt(), "a colleague's private row must not read differently from a missing one")
        assertEquals(messageOf(absent), messageOf(hiddenFromAdmin), "the refusal must not reveal that the row exists at all")
        assertTrue(answersAsAbsent(hiddenFromAdmin))

        // The admin's private provider is equally absent from the colleague, in both directions of one tenant.
        assertTrue(rowsNamed("/api/admin/model-providers/page", ownPrivateProvider, token = mateToken).isEmpty(), "a private provider must stay out of a colleague's list")
    }

    @Test
    @Order(10)
    fun `cleanup removes what this class created`() {
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/models/$matePrivateModelId", token = mateToken)))
        assertOk(deleteJson("/api/admin/models/$sharedModelId"))
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/models/$otherPrivateModelId", tenantId = otherTenant)))
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/model-providers/$otherProviderId", tenantId = otherTenant)))
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/model-providers/$clashProviderId", tenantId = otherTenant)))
        assertOk(deleteJson("/api/admin/model-providers/$ownPrivateProviderId"))
        assertOk(deleteJson("/api/admin/model-providers/$sharedProviderId"))
    }
}
