package dev.datacircle.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Each tool on the live API, through the agent loop of Koog, and on a real model.
 *
 * Runs with `DATACIRCLE_LIVE=1`; the model test also needs an Ollama API key in `OLLAMA_API_KEY`, for gpt-oss:120b on
 * ollama.com's OpenAI-compatible endpoint (`OLLAMA_BASE_URL` and `OLLAMA_MODEL` change them).
 */
class DatacircleToolsLiveTest {

    private val tools = DatacircleTools()

    @Test
    fun eachToolAnswersOnTheLiveApiThroughTheAgentLoop() = runBlocking {
        assumeTrue(System.getenv("DATACIRCLE_LIVE") == "1", "set DATACIRCLE_LIVE=1 to call the live API")
        val calls = listOf(
            "datacircle_list_apis" to mapOf<String, Any?>("query" to "email"),
            "datacircle_best_plans" to mapOf("api" to "email-finder", "limit" to 3),
            "datacircle_search_plans" to mapOf("query" to "hunter", "sort" to "price", "limit" to 3),
            "datacircle_provider" to mapOf("provider" to "Bright Data"),
        )
        for ((name, args) in calls) {
            val answer = runToolThroughAgent(tools, name, args)
            println("[$name $args]\n${answer.text.lines().take(4).joinToString("\n")}\n")
            assertTrue(!answer.text.startsWith("Error") && !answer.text.startsWith("Failed"), answer.text)
        }
        assertTrue(tools.bestPlans("email-finder", 3).contains("https://datacircle.dev/"))
        assertTrue(tools.bestPlans("no-such-api").contains("Call datacircle_list_apis"))
    }

    @Test
    fun aModelCallsEachTool() = runBlocking {
        assumeTrue(System.getenv("DATACIRCLE_LIVE") == "1", "set DATACIRCLE_LIVE=1 to call the live API")
        val key = System.getenv("OLLAMA_API_KEY")
        assumeTrue(!key.isNullOrBlank(), "set OLLAMA_API_KEY to run the tools on a model")
        val client = OpenAILLMClient(
            apiKey = key,
            settings = OpenAIClientSettings(baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "https://ollama.com"),
        )
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = System.getenv("OLLAMA_MODEL") ?: "gpt-oss:120b",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.Completion,
                LLMCapability.OpenAIEndpoint.Completions,
            ),
            contextLength = 131_072,
        )
        // Each question names its tool, so that a run calls each of the four at least once.
        val questions = listOf(
            "datacircle_list_apis" to "With datacircle_list_apis: which GTM APIs on Datacircle have linkedin in their name?",
            "datacircle_best_plans" to "With datacircle_best_plans: who are the 3 best providers of the email-finder API?",
            "datacircle_search_plans" to "With datacircle_search_plans: list Hunter's 3 cheapest plans, every API together.",
            "datacircle_provider" to "With datacircle_provider: on which APIs does Bright Data rank on Datacircle, and at what rank?",
        )
        for ((tool, question) in questions) {
            val answers = mutableListOf<ToolAnswer>()
            val agent = AIAgent(
                promptExecutor = MultiLLMPromptExecutor(mapOf(LLMProvider.OpenAI to client)),
                llmModel = model,
                systemPrompt = "Answer in a few lines from the Datacircle tools' answers only.",
                toolRegistry = ToolRegistry { tools(tools) },
                maxIterations = 20,
            ) {
                handleEvents {
                    onToolCallCompleted { event ->
                        answers += ToolAnswer(event.toolName, event.toolArgs.toString(), resultText(event.toolResult))
                    }
                }
            }
            val final = agent.run(question)
            println("[$question]")
            answers.forEach { println("  ${it.tool} ${it.args}: ${it.text.lines().first()}") }
            println("  -> ${final.lines().take(3).joinToString(" / ")}\n")
            assertTrue(
                answers.any { it.tool == tool && !it.text.startsWith("Error") },
                "$tool was not called with an answer: $answers",
            )
        }
    }
}
