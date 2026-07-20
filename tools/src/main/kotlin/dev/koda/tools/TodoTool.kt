package dev.koda.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class TodoTool : KodaTool {
    override val name = "todo"
    override val description =
        "Maintain the session task list. Pass the full updated list of items each time; " +
        "prefix completed items with [x]. Use it to plan multi-step work and track progress."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("items"),
        properties = mapOf(
            "items" to ("array" to "Full task list as strings; completed items start with [x]"),
        ),
    )

    override fun summarize(args: JsonObject) = "update todo list"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val items = args["items"]?.jsonArray
            ?: return ToolResult.error("missing required parameter: items")

        ctx.todos.clear()
        items.forEach { element ->
            val raw = element.jsonPrimitive.content
            val done = raw.startsWith("[x]", ignoreCase = true)
            val text = raw.removePrefix("[x]").removePrefix("[X]").trim()
            if (text.isNotEmpty()) ctx.todos += TodoItem(text, done)
        }

        val rendered = ctx.todos.joinToString("\n") { item ->
            (if (item.done) "  [x] " else "  [ ] ") + item.text
        }
        return ToolResult(if (rendered.isEmpty()) "(todo list cleared)" else "Todo list:\n$rendered")
    }
}
