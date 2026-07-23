package dev.koda.tools

/**
 * A minimal LCS-based unified diff (no dependency), used to surface the agent's
 * write/edit changes as reviewable diffs. Produces standard `@@` hunks with a
 * few lines of context; falls back to a coarse whole-file replace block for
 * very large inputs to bound the O(n·m) table.
 */
fun unifiedDiff(oldText: String, newText: String, path: String, context: Int = 3): String {
    if (oldText == newText) return ""
    val a = oldText.split("\n")
    val b = newText.split("\n")
    val header = "--- a/$path\n+++ b/$path\n"

    if (a.size > 4000 || b.size > 4000) {
        return header + "@@ -1,${a.size} +1,${b.size} @@\n" +
            a.joinToString("") { "-$it\n" } + b.joinToString("") { "+$it\n" }
    }

    val n = a.size; val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    }

    val tags = ArrayList<Char>(); val text = ArrayList<String>()
    var i = 0; var j = 0
    fun push(t: Char, s: String) { tags.add(t); text.add(s) }
    while (i < n && j < m) {
        if (a[i] == b[j]) { push(' ', a[i]); i++; j++ }
        else if (dp[i + 1][j] >= dp[i][j + 1]) { push('-', a[i]); i++ }
        else { push('+', b[j]); j++ }
    }
    while (i < n) { push('-', a[i]); i++ }
    while (j < m) { push('+', b[j]); j++ }

    // Running old/new line numbers per op.
    val oldNo = IntArray(tags.size); val newNo = IntArray(tags.size)
    var oi = 1; var ni = 1
    for (k in tags.indices) {
        oldNo[k] = oi; newNo[k] = ni
        when (tags[k]) { ' ' -> { oi++; ni++ }; '-' -> oi++; '+' -> ni++ }
    }

    // A line is "interesting" if within `context` of a change; contiguous runs are hunks.
    val interesting = BooleanArray(tags.size)
    for (k in tags.indices) if (tags[k] != ' ') {
        for (d in -context..context) (k + d).let { if (it in tags.indices) interesting[it] = true }
    }

    val out = StringBuilder(header)
    var k = 0
    while (k < tags.size) {
        if (!interesting[k]) { k++; continue }
        var end = k
        while (end + 1 < tags.size && interesting[end + 1]) end++
        val oldCount = (k..end).count { tags[it] != '+' }
        val newCount = (k..end).count { tags[it] != '-' }
        out.append("@@ -${oldNo[k]},$oldCount +${newNo[k]},$newCount @@\n")
        for (t in k..end) out.append(tags[t]).append(text[t]).append('\n')
        k = end + 1
    }
    return out.toString()
}
