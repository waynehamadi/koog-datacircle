package dev.datacircle.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolFromCallable
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels

/** What the agent's tool node gave back for one tool call: the tool's text and whether it failed. */
data class ToolAnswer(val tool: String, val args: String, val text: String)

/** Reads the text of a tool result, which Koog encodes as a JSON string. */
fun resultText(result: Any?): String {
    val raw = result.toString()
    return Json.parse(raw) as? String ?: raw
}

/**
 * Runs a Koog agent whose LLM is Koog's own mock: it asks for one call of [toolName] with [args], then answers.
 * The agent decodes the call, runs the tool from its registry and sends the result back, as with a real model.
 */
suspend fun runToolThroughAgent(tools: DatacircleTools, toolName: String, args: Map<String, Any?>): ToolAnswer {
    val registry = ToolRegistry { tools(tools) }
    val tool = registry.getTool(toolName) as ToolFromCallable<*>
    val toolArgs = ToolFromCallable.Args(
        args.mapKeys { (name, _) -> tool.callable.parameters.first { it.name == name } },
    )
    val question = "Call $toolName once."
    val executor = getMockExecutor {
        mockLLMToolCall(tool, toolArgs) onRequestEquals question
        mockLLMAnswer("Done.").asDefaultResponse
    }
    val answers = mutableListOf<ToolAnswer>()
    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = OpenAIModels.Chat.GPT4o,
        toolRegistry = registry,
    ) {
        handleEvents {
            onToolCallCompleted { event ->
                answers += ToolAnswer(event.toolName, event.toolArgs.toString(), resultText(event.toolResult))
            }
            onToolCallFailed { event ->
                answers += ToolAnswer(event.toolName, event.toolArgs.toString(), "Failed: ${event.message}")
            }
        }
    }
    val final = agent.run(question)
    check(final == "Done.") { "the agent ended on \"$final\"" }
    return answers.single()
}
