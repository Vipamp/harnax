package com.agnetix.harnax.admin.util

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Outbound JSON GET and form POST to a URL an admin typed in or an upstream document named (OAuth
 * discovery, token exchange, revocation).
 *
 * Its own bean for two reasons: the OAuth logic can then be tested without opening a socket, and
 * the guards that belong to this kind of request - http(s) only, no redirects, a deadline, a body cap
 * - are enforced once here instead of at every call site.
 *
 * The guards are a floor, not a policy. They keep one bad document from turning harnax into a
 * requester of cloud metadata services, and they keep a chatty upstream from exhausting memory or a
 * request thread. They do not make an arbitrary host safe to talk to, and cannot: the address checked
 * here is resolved again by the connection itself, so a name that later points inward is still
 * reachable. Whether internal ranges are reachable at all stays a network concern.
 */
@Component
class RemoteJsonFetcher(
    private val objectMapper: ObjectMapper,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        // Following redirects would let one approved URL move the request to a host the admin never
        // meant to contact, so a 3xx is simply a failed candidate here.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * @throws RemoteFetchException when the host is unreachable, refused by the address floor, the
     * deadline passes or the body is too large. A non-2xx answer is not an exception: discovery reads
     * [RemoteFetch.status] to tell "no metadata at this path" from "nothing listening".
     */
    fun fetch(url: String): RemoteFetch {
        val shown = redactUrl(url)
        val uri = validate(url, shown)
        val request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build()
        return exchange(request, shown)
    }

    /**
     * `application/x-www-form-urlencoded` POST, used for the token exchange and for revocation
     * (RFC 6749 §4.1.3, RFC 7009). Both addresses come from a discovery snapshot, which is exactly why
     * this goes through the same component as [fetch]: the protocol whitelist, the metadata-address
     * floor, the refusal to follow redirects, the deadline and the body cap are the guards a request
     * carrying a code, a client secret or a token needs most.
     *
     * Nothing about the form is ever put in an exception message: [shown] is the URL with its userinfo
     * redacted, and that is all that travels.
     */
    fun postForm(
        url: String,
        form: Map<String, String>,
    ): RemoteFetch {
        val shown = redactUrl(url)
        val uri = validate(url, shown)
        val body = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return exchange(request, shown)
    }

    /**
     * One send, one bounded read, one parse - what [fetch] and [postForm] have in common.
     */
    private fun exchange(
        request: HttpRequest,
        shown: String,
    ): RemoteFetch {
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RemoteFetchException("Request to $shown was interrupted")
        } catch (e: Exception) {
            throw RemoteFetchException("Request to $shown failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val bytes = try {
            response.body().use { readWithinDeadline(it, shown) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RemoteFetchException("Request to $shown was interrupted")
        } catch (e: RemoteFetchException) {
            throw e
        } catch (e: Exception) {
            // A reset or a truncated chunked body is this candidate failing, not discovery crashing:
            // callers catch this type and move on to the next URL.
            throw RemoteFetchException("Reading the response from $shown failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val json = if (bytes.isNotEmpty()) {
            try {
                // Only an object is a candidate: a JSON array or scalar answers "there is nothing
                // published at this path" as surely as HTML does. An error body is parsed too,
                // because RFC 6749 §5.2 puts the reason for a refused token exchange in the body of
                // a 400 - [RemoteFetch.ok] is still decided by the status alone, so reading a
                // document as metadata stays a deliberate act by the caller.
                objectMapper.readTree(bytes).takeIf { it.isObject }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        return RemoteFetch(
            status = response.statusCode(),
            // Some gateways send one challenge per header and the resource_metadata pointer can be in
            // either of them.
            wwwAuthenticate = response.headers().allValues("WWW-Authenticate").joinToString(", ").takeIf { it.isNotBlank() },
            json = json,
        )
    }

    /**
     * Read the body with both a size cap and a deadline.
     *
     * The request timeout covers the exchange up to the response headers; draining an
     * `ofInputStream` body happens on this thread, so a host that trickles bytes needs its own bound.
     * Each read returns as soon as any byte arrives, so the deadline is noticed between chunks.
     */
    private fun readWithinDeadline(
        stream: InputStream,
        shown: String,
    ): ByteArray {
        val deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos()
        val out = ByteArrayOutputStream(MAX_BODY_BYTES + 1)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (out.size() > MAX_BODY_BYTES) {
                throw RemoteFetchException("Response from $shown exceeds the $MAX_BODY_BYTES byte limit")
            }
            if (System.nanoTime() > deadline) {
                throw RemoteFetchException("Response from $shown did not complete within ${REQUEST_TIMEOUT.seconds}s")
            }
            val read = stream.read(buffer)
            if (read == -1) {
                return out.toByteArray()
            }
            out.write(buffer, 0, read)
        }
    }

    private fun validate(
        url: String,
        shown: String,
    ): URI {
        val uri = try {
            URI.create(url.trim())
        } catch (e: Exception) {
            throw RemoteFetchException("Not a valid URL: $shown")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw RemoteFetchException("Only http(s) URLs can be requested, got: $shown")
        }
        if (uri.host.isNullOrBlank()) {
            throw RemoteFetchException("URL has no host: $shown")
        }
        refuseMetadataTargets(uri.host, shown)
        return uri
    }

    /**
     * The one address an upstream document must never get harnax to read: a cloud metadata service,
     * which answers on the link-local range and would hand back credentials of this host. Loopback and
     * the private ranges stay reachable on purpose - self-hosted authorization servers live there,
     * including in this project's own docker-compose setup.
     */
    private fun refuseMetadataTargets(
        host: String,
        shown: String,
    ) {
        if (host.lowercase() in METADATA_HOST_NAMES) {
            throw RemoteFetchException("Refusing to request the metadata service host in $shown")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw RemoteFetchException("Request to $shown failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            throw RemoteFetchException("Request to $shown failed: ${e.message ?: e.javaClass.simpleName}")
        }
        addresses.firstOrNull { it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }?.let {
            throw RemoteFetchException("Refusing to request $shown: $host resolves to ${it.hostAddress}")
        }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** AS metadata documents are a few kilobytes; anything larger is not one. */
        private const val MAX_BODY_BYTES = 64 * 1024

        private val METADATA_HOST_NAMES = setOf("metadata.google.internal", "metadata.goog")
    }
}

private val USER_INFO_PATTERN = """^(\w+://)[^\s/@]+@""".toRegex()

/**
 * Drop the userinfo part of a URL. These strings end up in log lines and in the error body an
 * administrator sees, and `http://user:token@host` is a legal URL someone might well paste.
 */
fun redactUrl(url: String): String = USER_INFO_PATTERN.replace(url.trim()) { "${it.groupValues[1]}***@" }

/**
 * The canonical form of an issuer as this feature stores and compares it: trimmed, with one trailing
 * slash dropped.
 *
 * Nothing else is touched. RFC 8414 says `issuer` is compared as a string, so folding case or
 * rewriting the path would let two different authorization servers share one client registration.
 * Both the discovery that writes the row and the flow that reads it back derive the value here,
 * otherwise an admin who typed `https://as.example/` configures one row and authorizes against none.
 */
fun normalizeIssuer(value: String?): String = value?.trim()?.removeSuffix("/").orEmpty()

/**
 * A string member of an object, null when absent, not a string, or blank.
 */
fun JsonNode.optString(name: String): String? = path(name).takeIf { it.isString }?.asText()?.takeIf { it.isNotBlank() }

/**
 * The string members of an array, empty when the member is absent or not an array.
 */
fun JsonNode.optStringList(name: String): List<String> = path(name)
    .takeIf { it.isArray }
    ?.filter { it.isString }
    ?.map { it.asText() }
    ?.filter { it.isNotBlank() }
    .orEmpty()

/**
 * One answered request: status line, the header discovery falls back to, and the parsed body when
 * there was a JSON object to parse.
 */
data class RemoteFetch(
    val status: Int,
    val wwwAuthenticate: String?,
    val json: JsonNode?,
) {
    val ok: Boolean get() = status in 200..299

    fun field(name: String): String? = json?.optString(name)

    fun array(name: String): List<String> = json?.optStringList(name).orEmpty()
}

class RemoteFetchException(
    message: String,
) : RuntimeException(message)
