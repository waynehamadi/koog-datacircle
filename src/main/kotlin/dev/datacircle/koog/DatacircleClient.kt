package dev.datacircle.koog

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.future.await

/**
 * Low-level client for the open Datacircle API at `https://api.datacircle.dev/v1`.
 *
 * The API needs no key and no account. It allows 60 calls a minute from one address.
 */
internal class DatacircleClient(baseUrl: String?, private val timeout: Duration) {

    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** `GET /apis`: every API on the sheet, in its order, an array. */
    suspend fun listApis(): Any? = get("/apis")

    /** `GET /apis/{slug}/best`: one API's plans, best first by score. */
    suspend fun bestPlans(slug: String?, limit: Int?): Any? =
        get("/apis/" + encodePath(normalizeSlug(slug, "api")) + "/best?limit=" + normalizeLimit(limit))

    /** `GET /plans`: the plans of every API, or of one, by words in the provider's or plan's name. */
    suspend fun searchPlans(api: String?, query: String?, sort: String?, limit: Int?, offset: Int?): Any? {
        val path = StringBuilder("/plans?sort=")
            .append(normalizeSort(sort))
            .append("&limit=")
            .append(normalizeLimit(limit))
            .append("&offset=")
            .append(normalizeOffset(offset))
        appendOptional(path, "api", api)
        appendOptional(path, "query", query)
        return get(path.toString())
    }

    /** `GET /providers/{slug}`: one provider's plans and rank on each API it sells. */
    suspend fun provider(slug: String?): Any? = get("/providers/" + encodePath(providerSlug(slug)))

    private suspend fun get(path: String): Any? {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DatacircleException(transportErrorMessage(e), e)
        }
        val status = response.statusCode()
        val body: String? = response.body()
        if (status !in 200..299) {
            throw DatacircleException(httpErrorMessage(status, body), statusCode = status)
        }
        if (body.isNullOrBlank()) {
            throw DatacircleException("The Datacircle API returned an empty response.")
        }
        return try {
            Json.parse(body)
        } catch (e: IllegalArgumentException) {
            throw DatacircleException("The Datacircle API returned invalid JSON.", e)
        }
    }

    /** Says why no answer came: a timeout, an unknown host, a TLS failure, a refused or a lost connection. */
    private fun transportErrorMessage(error: Throwable): String {
        val causes = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).toList()
        return when {
            causes.any { it is HttpTimeoutException } ->
                "The Datacircle API did not answer within ${timeout.toSeconds()} s. Retry in a moment."
            causes.any { it is UnknownHostException || it is UnresolvedAddressException } ->
                "The Datacircle API's host ${URI.create(baseUrl).host} could not be found. " +
                    "Check the network and the base URL."
            causes.any { it is SSLException } ->
                "The secure connection to the Datacircle API failed (TLS). Check the network and the base URL."
            causes.any { it is ConnectException } ->
                "The Datacircle API refused the connection. Check the network and the base URL."
            causes.any { it is IOException } ->
                "The connection to the Datacircle API was lost before it answered. Retry in a moment."
            else -> "The Datacircle API request failed: ${error.javaClass.simpleName}."
        }
    }

    internal companion object {
        const val DEFAULT_BASE_URL: String = "https://api.datacircle.dev/v1"
        const val DEFAULT_LIMIT: Int = 10
        const val MAX_LIMIT: Int = 50
        val SORTS: List<String> = listOf("price", "provider", "score")

        private const val USER_AGENT = "koog-datacircle/0.1.0"
        private const val MAX_ERROR_LENGTH = 300

        /** Turns a provider's name into its slug: accents dropped, lowercase letters and digits, the rest a hyphen. */
        fun providerSlug(name: String?): String {
            val text = ensureNotBlank(name, "provider")
            val slug = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            require(slug.isNotEmpty()) {
                "Datacircle writes each provider's name in Latin letters, so \"${text.trim()}\" finds none. " +
                    "Pass the name in Latin letters or its website's domain, like ihuyi for ihuyi.com, " +
                    "or find the provider with datacircle_search_plans."
            }
            return slug
        }

        private fun httpErrorMessage(statusCode: Int, body: String?): String {
            val retry = "The API allows 60 calls a minute from one address. Retry in a minute."
            val reason = errorSentence(body)?.let { if (statusCode == 429) "$it $retry" else it }
                ?: when (statusCode) {
                    400 -> "Invalid request. Check the tool arguments."
                    404 -> "Not found."
                    429 -> retry
                    else -> "Request could not be completed."
                }
            return "Datacircle API request failed: HTTP $statusCode. $reason"
        }

        /** The API's own one-line reason, from its `{"error": "..."}` body, or `null`. */
        private fun errorSentence(body: String?): String? {
            if (body.isNullOrBlank()) {
                return null
            }
            val parsed = try {
                Json.parse(body)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val error = ((parsed as? Map<*, *>)?.get("error") as? String)?.trim()
            if (error.isNullOrEmpty()) {
                return null
            }
            val sentence = if (error.length > MAX_ERROR_LENGTH) error.take(MAX_ERROR_LENGTH - 3) + "..." else error
            return if (sentence.endsWith(".")) sentence else "$sentence."
        }

        private fun appendOptional(path: StringBuilder, name: String, value: String?) {
            if (!value.isNullOrBlank()) {
                path.append('&').append(name).append('=').append(encode(value.trim()))
            }
        }

        private fun normalizeLimit(limit: Int?): Int {
            val value = limit ?: DEFAULT_LIMIT
            require(value in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
            return value
        }

        private fun normalizeOffset(offset: Int?): Int {
            val value = offset ?: 0
            require(value >= 0) { "offset must be 0 or more" }
            return value
        }

        private fun normalizeSort(sort: String?): String {
            if (sort.isNullOrBlank()) {
                return "score"
            }
            val normalized = sort.trim().lowercase(Locale.ROOT)
            require(normalized in SORTS) { "sort must be one of $SORTS" }
            return normalized
        }

        private fun normalizeSlug(slug: String?, name: String): String =
            ensureNotBlank(slug, name).trim().lowercase(Locale.ROOT)

        private fun ensureNotBlank(value: String?, name: String): String {
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$name must not be empty")
            }
            return value
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun encodePath(value: String): String = encode(value).replace("+", "%20")

        private fun normalizeBaseUrl(baseUrl: String?): String {
            val value = (if (baseUrl.isNullOrBlank()) DEFAULT_BASE_URL else baseUrl.trim()).trimEnd('/')
            val uri = try {
                URI.create(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("baseUrl is not a valid URL", e)
            }
            val scheme = uri.scheme
            require(scheme != null && (scheme.equals("http", true) || scheme.equals("https", true))) {
                "baseUrl must use HTTP or HTTPS"
            }
            require(uri.host != null) { "baseUrl must include a host" }
            return value
        }
    }
}
