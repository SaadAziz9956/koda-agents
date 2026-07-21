package dev.koda.core.engine

/**
 * A curated denylist of catastrophic shell patterns. A match forces user
 * approval even in relaxed modes (accept-edits / yolo / always-allowed) — a
 * guardrail against an honest-but-wrong agent and against injected commands.
 *
 * It is deliberately conservative (whole-disk deletion, pipe-to-shell, disk
 * writes, privilege escalation) — not a general safety filter. It's a
 * guardrail, not a boundary; the sandbox is the boundary.
 */
object DangerousCommands {

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+(/|~|/\*)""") to "recursive delete of a root/home path",
        Regex("""(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh)\b""") to "pipe remote script into a shell",
        Regex("""\bsudo\b""") to "privilege escalation (sudo)",
        Regex("""\bdd\b[^\n]*\bof=/dev/""") to "raw write to a device",
        Regex("""\b(mkfs|fdisk|diskutil\s+(erase|partition))\b""") to "format/partition a disk",
        Regex(""">\s*/dev/(sd|disk|nvme|hd)""") to "overwrite a block device",
        Regex("""\bchmod\s+-R\s+[0-7]{3,4}\s+/(\s|$)""") to "recursive chmod of /",
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:""") to "fork bomb",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "shut down / reboot the machine",
        Regex("""\bgit\s+push\b[^\n]*--force\b|\bgit\s+push\b[^\n]*\s-f\b""") to "force-push (history rewrite)",
    )

    /** Returns a human reason if [command] matches a dangerous pattern, else null. */
    fun match(command: String): String? {
        val normalized = command.replace(Regex("\\s+"), " ").trim()
        return PATTERNS.firstOrNull { it.first.containsMatchIn(normalized) }?.second
    }
}
