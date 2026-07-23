package dev.koda.tools

import kotlinx.serialization.json.JsonObject

/**
 * Ask the user to choose when a request is ambiguous. The real handshake lives
 * in the core (the gate surfaces a picker and blocks for the answer); this
 * class only supplies the name/description/schema the model sees. Its
 * [execute] is never reached — the gate intercepts `ask` before dispatch.
 */
class AskTool : KodaTool {
    override val name = "ask"
    override val description =
        "Ask the user to choose when the request is ambiguous or underspecified. " +
        "Give a short question and 2–5 options, each with a label and a one-line " +
        "description. The user may pick one, type their own answer, or choose to " +
        "discuss it. Prefer this over guessing when the answer changes what you do."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("question"),
        properties = mapOf("question" to ("string" to "The question to ask the user")),
    )

    override fun summarize(args: JsonObject) = "ask: ${args.optionalString("question")?.take(80) ?: ""}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult("(clarification handled by the surface)")
}
