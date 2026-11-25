package dev.datacircle.koog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Koog tools for the open [Datacircle](https://datacircle.dev) API, which benchmarks the GTM APIs (email finders,
 * company enrichment, LinkedIn data and more) and ranks every plan of each by its score out of 100 and its price
 * per 1,000.
 *
 * Add an instance to an agent's tool registry beside the app's own tools. The API needs no key and no account.
 * It allows 60 calls a minute from one address. Its spec is at `https://api.datacircle.dev/v1/openapi.json`.
 *
 * ```kotlin
 * val agent = AIAgent(
 *     promptExecutor = simpleOpenAIExecutor(apiKey),
 *     llmModel = OpenAIModels.Chat.GPT4o,
 *     toolRegistry = ToolRegistry { tools(DatacircleTools()) },
 * )
 * ```
 *
 * @param baseUrl the API's base URL, `https://api.datacircle.dev/v1` by default
 * @param timeout the connection and answer timeout, 30 s by default
 */
@LLMDescription("Datacircle: the GTM APIs, their plans and who is best for each, from Datacircle's open API")
public class DatacircleTools @JvmOverloads constructor(
    baseUrl: String = DatacircleClient.DEFAULT_BASE_URL,
    timeout: Duration = Duration.ofSeconds(30),
) : ToolSet {

    private val client = DatacircleClient(baseUrl, timeout)

    /** Lists the GTM APIs Datacircle ranks, each with its slug, its counts and its best plan. */
    @Tool(customName = "datacircle_list_apis")
    @LLMDescription(
        "List the GTM APIs that Datacircle ranks (email finder, company enrichment, LinkedIn profile and more), " +
            "each with its slug, how many plans and providers it has, and its best plan."
    )
    public suspend fun listApis(
        @LLMDescription("Words that must all appear in the API's name or slug, like email or linkedin. Leave empty to list every API")
        query: String? = null,
    ): String = answer { formatApis(client.listApis(), query) }

    /** Reads one API's best plans, best first by score. */
    @Tool(customName = "datacircle_best_plans")
    @LLMDescription(
        "Get the best plans of one GTM API, best first by Datacircle's score out of 100, with each plan's price " +
            "per 1,000 and its page."
    )
    public suspend fun bestPlans(
        @LLMDescription("The API's slug, as datacircle_list_apis gives it, like email-finder")
        api: String,
        @LLMDescription("Number of plans from 1 through 50. Defaults to 10")
        limit: Int? = null,
    ): String = answer(
        hint = { if (isStatus(it, 404)) " Call datacircle_list_apis for every API's slug." else "" },
    ) { formatBestPlans(client.bestPlans(api, limit)) }

    /** Searches the plans of every API, or of one, by provider or plan name. */
    @Tool(customName = "datacircle_search_plans")
    @LLMDescription(
        "Search the plans of the GTM APIs on Datacircle by API, and by words in the provider's or plan's name, " +
            "sorted by score (best first), provider (A to Z) or price (lowest $ per 1,000 first)."
    )
    public suspend fun searchPlans(
        @LLMDescription("The API's name or slug, like email-finder. A provider's name goes in query. Defaults to every API")
        api: String? = null,
        @LLMDescription("Words that must all appear in the provider's or plan's name, like hunter")
        query: String? = null,
        @LLMDescription("Sort: score, provider or price. Defaults to score")
        sort: String? = null,
        @LLMDescription("Number of plans from 1 through 50. Defaults to 10")
        limit: Int? = null,
        @LLMDescription("Number of found plans to skip first. Defaults to 0")
        offset: Int? = null,
    ): String = answer(
        hint = {
            val unknownApi = !api.isNullOrBlank() && (isStatus(it, 400) || isStatus(it, 404))
            if (unknownApi) " Call datacircle_list_apis for every API's name and slug." else ""
        },
    ) { formatSearch(client.searchPlans(api, query, sort, limit, offset), offset ?: 0) }

    /** Reads one provider's rank on each API it sells. */
    @Tool(customName = "datacircle_provider")
    @LLMDescription(
        "Get one provider's rank on each GTM API it sells on Datacircle, with its best plan there. A provider is " +
            "a company that sells the APIs, like Hunter or Bright Data."
    )
    public suspend fun provider(
        @LLMDescription("The provider's name or slug, like Bright Data or bright-data")
        provider: String,
    ): String = answer { formatProvider(client.provider(provider)) }

    private companion object {
        const val MAX_TEXT_LENGTH = 120
        const val MAX_PROVIDER_APIS = 100
        const val NEW_LINE = "\n"

        /** Runs a tool, and turns its failure into a line that starts with `Error:`, so that the model can act on it. */
        inline fun answer(hint: (RuntimeException) -> String = { "" }, block: () -> String): String =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                formatError(e) + hint(e)
            }

        fun formatApis(apis: Any?, query: String?): String {
            if (apis !is List<*>) {
                throw unexpected("the list of APIs is not a list")
            }
            if (apis.isEmpty()) {
                return "The Datacircle API returned no APIs."
            }
            val words = words(query)
            val lines = ArrayList<String>()
            for (api in apis) {
                val name = required(api, "api")
                val slug = required(api, "slug")
                if (!matchesAll("$name $slug", words)) {
                    continue
                }
                val line = StringBuilder("- ")
                    .append(truncate(name))
                    .append(" (")
                    .append(slug)
                    .append("): ")
                    .append(count(field(api, "plans"), "plan"))
                    .append(" from ")
                    .append(count(field(api, "providers"), "provider"))
                    .append('.')
                val best = field(api, "best")
                if (best is Map<*, *>) {
                    line.append(" Best: ")
                        .append(truncate(required(best, "provider")))
                        .append(", ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), null))
                        .append('.')
                }
                lines.add(line.toString())
            }
            if (lines.isEmpty()) {
                return "No API's name or slug holds every word of \"${query!!.trim()}\". Leave the query empty to " +
                    "list ${apis.size} APIs."
            }
            return lines.joinToString(NEW_LINE) + NEW_LINE + "${lines.size} of ${apis.size} APIs. " +
                "Pass a slug to datacircle_best_plans or datacircle_search_plans."
        }

        fun formatBestPlans(response: Any?): String {
            val plans = list(response, "plans")
            val result = StringBuilder()
                .append(required(response, "api"))
                .append(" (")
                .append(required(response, "slug"))
                .append("): ")
            if (plans.isEmpty()) {
                return result.append("no plans found.").toString()
            }
            result.append("the best ")
                .append(plans.size)
                .append(" of ")
                .append(count(field(response, "total"), "plan"))
                .append('.')
            appendIfPresent(result, "Sheet", text(response, "sheetPage"))
            for (plan in plans) {
                result.append(NEW_LINE).append(formatPlan(plan, withApi = false))
            }
            return result.toString()
        }

        fun formatSearch(response: Any?, requestedOffset: Int): String {
            val plans = list(response, "plans")
            val total = field(response, "total")
            val offset = (field(response, "offset") as? BigDecimal)?.toInt() ?: requestedOffset
            if (plans.isEmpty()) {
                if (total is BigDecimal && total.signum() > 0) {
                    return "Found ${count(total, "plan")}, but the offset $offset is past the last one. " +
                        "Use an offset below ${total.toPlainString()}."
                }
                return "No plans found."
            }
            val result = StringBuilder("Found ")
                .append(count(total, "plan"))
                .append("; showing ")
                .append(offset + 1)
                .append(" to ")
                .append(offset + plans.size)
                .append('.')
            appendIfPresent(result, "Sheet", text(response, "sheetPage"))
            for (plan in plans) {
                result.append(NEW_LINE).append(formatPlan(plan, withApi = true))
            }
            return result.toString()
        }

        fun formatProvider(response: Any?): String {
            val apis = list(response, "apis")
            val result = StringBuilder(required(response, "provider"))
            val website = text(response, "website")
            if (website.isNotEmpty()) {
                result.append(" (").append(website).append(')')
            }
            if (apis.isEmpty()) {
                return result.append(" sells no API on Datacircle.").toString()
            }
            result.append(" sells ").append(plural(apis.size.toLong(), "API"))
                .append(". Its rank among each API's providers:")
            for ((shown, api) in apis.withIndex()) {
                if (shown == MAX_PROVIDER_APIS) {
                    result.append(NEW_LINE).append("And ").append(plural((apis.size - shown).toLong(), "more API"))
                        .append('.')
                    break
                }
                val rank = field(api, "rank")
                val plans = list(api, "plans")
                result.append(NEW_LINE)
                    .append("- ")
                    .append(truncate(required(api, "api")))
                    .append(" (")
                    .append(required(api, "slug"))
                    .append("): ")
                    .append(if (rank is BigDecimal) "rank ${rank.toInt()}" else "not ranked")
                    .append(", ")
                    .append(plural(plans.size.toLong(), "plan"))
                    .append('.')
                val best = plans.filter { field(it, "score") is BigDecimal }
                    .maxByOrNull { field(it, "score") as BigDecimal }
                if (best != null) {
                    result.append(" Best: ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), field(best, "per")))
                        .append('.')
                }
            }
            return result.toString()
        }

        fun formatPlan(plan: Any?, withApi: Boolean): String {
            val line = StringBuilder()
            val rank = field(plan, "rank")
            line.append(if (!withApi && rank is BigDecimal) "${rank.toInt()}. " else "- ")
                .append(truncate(required(plan, "provider")))
                .append(", ")
                .append(truncate(required(plan, "plan")))
            if (withApi) {
                line.append(" (").append(truncate(required(plan, "api"))).append(')')
            }
            line.append(": ")
            if (withApi && rank is BigDecimal) {
                line.append("rank ").append(rank.toInt()).append(", ")
            }
            line.append(score(field(plan, "score")))
                .append(", ")
                .append(price(field(plan, "perThousand"), field(plan, "per")))
            val notFound = field(plan, "notFoundPerThousand")
            if (notFound is BigDecimal) {
                line.append(", ").append(dollars(notFound)).append(" per 1,000 not found")
            }
            val billing = text(plan, "billing")
            if (billing.isNotEmpty()) {
                line.append(", ").append(billing)
            }
            val latency = field(plan, "latencyMs")
            if (latency is BigDecimal) {
                line.append(", median ").append(latency.toLong()).append(" ms")
            }
            line.append('.')
            val page = text(plan, "page")
            if (page.isNotEmpty()) {
                line.append(' ').append(page)
            }
            return line.toString()
        }

        fun score(score: Any?): String =
            if (score is BigDecimal) {
                "score " + score.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "/100"
            } else {
                "not scored"
            }

        fun price(perThousand: Any?, per: Any?): String {
            if (perThousand !is BigDecimal) {
                return "no listed price"
            }
            val unit = if (per is String && per.isNotBlank()) " ${per}s" else ""
            return dollars(perThousand) + " per 1,000" + unit
        }

        fun dollars(amount: BigDecimal): String = "$" + amount.stripTrailingZeros().toPlainString()

        fun count(number: Any?, word: String): String =
            if (number is BigDecimal) plural(number.toLong(), word) else "? ${word}s"

        fun plural(count: Long, word: String): String =
            String.format(Locale.ROOT, "%,d", count) + " " + word + if (count == 1L) "" else "s"

        fun words(query: String?): List<String> =
            if (query.isNullOrBlank()) emptyList() else fold(query).trim().split(Regex("[\\s-]+"))

        fun matchesAll(text: String, words: List<String>): Boolean {
            val folded = fold(text)
            return words.all { folded.contains(it) }
        }

        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

        fun appendIfPresent(result: StringBuilder, label: String, value: String) {
            if (value.isNotEmpty()) {
                result.append(' ').append(label).append(": ").append(value)
            }
        }

        fun field(node: Any?, name: String): Any? = (node as? Map<*, *>)?.get(name)

        fun required(node: Any?, name: String): String {
            val value = field(node, name)
            if (value !is String || value.isBlank()) {
                throw unexpected("$name is missing")
            }
            return value
        }

        fun list(node: Any?, name: String): List<*> = field(node, name) as? List<*> ?: throw unexpected("$name is not a list")

        fun text(node: Any?, name: String): String = (field(node, name) as? String)?.takeIf { it.isNotBlank() } ?: ""

        fun unexpected(what: String) = DatacircleException(
            "The Datacircle API returned an answer of an unexpected shape: $what. Retry later, or read " +
                "https://api.datacircle.dev/v1/openapi.json."
        )

        fun truncate(text: String): String =
            if (text.length <= MAX_TEXT_LENGTH) text else text.take(MAX_TEXT_LENGTH - 3) + "..."

        fun isStatus(error: RuntimeException, statusCode: Int): Boolean =
            error is DatacircleException && error.statusCode == statusCode

        fun formatError(error: RuntimeException): String {
            val message = error.message
            return if (message.isNullOrBlank()) "Error: the Datacircle API request failed." else "Error: $message"
        }
    }
}
