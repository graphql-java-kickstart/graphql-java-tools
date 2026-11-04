package graphql.kickstart.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CommentConventionsTest {

    @Test
    fun `comments carry no issue numbers, history or versions`() {
        val violations = listOf(File("src/main"), File("src/test")).asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .sortedBy { it.path }
            .flatMap { CommentLint.check(it.path, it.readText(), LocalDate.now()) }
            .toList()

        if (violations.isNotEmpty()) {
            fail(violations.joinToString("\n", "${violations.size} comment(s) break the comment conventions:\n"))
        }
    }

    @Test
    fun `lint matches whole comment blocks and honors only valid markers`() {
        fun rules(source: String) = CommentLint.check("Example.kt", source, LocalDate.of(2026, 11, 4)).map { it.rule }

        assertEquals(listOf("history"), rules("/* this used to\n * be slow */"))
        assertEquals(listOf("history"), rules("// this used to\n// be slow"))
        assertEquals(listOf("issue-number"), rules("// see #999"))
        assertEquals(listOf("version"), rules("/** @since 15.0 */"))
        assertEquals(emptyList<String>(), rules("// TODO reject in 16.0.0 (#999)"))
        assertEquals(emptyList<String>(), rules("val s = \"// see #999\""))
        assertEquals(emptyList<String>(), rules("// unchanged lint-allow history until 2026-12-01 by oryan: domain term"))
        assertEquals(listOf("lint-allow", "history"), rules("// unchanged lint-allow history until 2026-11-03 by oryan: expired"))
        assertEquals(listOf("lint-allow", "history"), rules("// unchanged lint-allow history until 2026-12-01 by oryan:"))
        assertEquals(listOf("lint-allow", "history"), rules("// unchanged lint-allow history until 2027-06-01 by oryan: too late"))
        assertEquals(listOf("history"), rules("// unchanged lint-allow version until 2026-12-01 by oryan: other rule"))
    }
}

internal object CommentLint {

    class Violation(val path: String, val line: Int, val rule: String, val match: String, val lineText: String, val fix: String) {
        override fun toString() = "$path:$line [$rule] \"$match\" in: $lineText\n    fix: $fix"
    }

    private class Rule(val id: String, val pattern: Regex, val fix: String)

    private const val MOVE_TO_PR = "move the reason into the PR description"

    private val historyPhrases = listOf(
        """\bused to be\b""",
        """\bunchanged\b""",
        """\bpreviously\b""",
        """\bno longer\b""",
        """\bany ?more\b""",
        """\bbefore (?:this|the|that) (?:change|check|fix|pr|commit)\b""",
        """\b(?:before|after|until|since) #\d+""",
        """\bas (?:it|they) did\b""",
        """\b(?:as|like|than) before\b""",
        """\bstill (?:builds?|works?|building|working)\b""",
        """\bkeeps? (?:those |these |such |existing |old )?(?:schemas? )?(?:building|working)\b""",
        """\bworkaround for\b""",
        """\b(?:is|are|does|do|returns?|throws?) now\b""",
        """\bnow (?:also |always |only )?(?:returns?|throws?|fails?|builds?|keeps?|logs?|warns?|works?|supports?)\b""",
    )

    private val rules = listOf(
        Rule(
            "issue-number",
            Regex("""(?<![\w&])#\d+\b|github\.com/\S+/(?:issues|pull)/\d+""", RegexOption.IGNORE_CASE),
            "$MOVE_TO_PR; an issue number belongs only in `// TODO <change> in X.0.0 (#N)`"
        ),
        Rule(
            "history",
            Regex(historyPhrases.joinToString("|"), RegexOption.IGNORE_CASE),
            "say what the code does and why, not how it got here; $MOVE_TO_PR"
        ),
        Rule(
            "version",
            Regex("""@since\b|\bsince (?:v|version )?\d+\.\d+""", RegexOption.IGNORE_CASE),
            "drop the version; history goes in the release notes"
        ),
    )

    private const val MARKER_MAX_DAYS = 90L
    private val marker = Regex("""lint-allow.*""")
    private val validMarker = Regex("""lint-allow ([\w-]+) until (\d{4}-\d{2}-\d{2}) by [\w-]+: \S.*""")
    private val markerFix = "reword the comment and drop the marker, or write `lint-allow <rule> until YYYY-MM-DD by <name>: " +
        "<reason>` with a rule from ${rules.joinToString { it.id }} and a date at most $MARKER_MAX_DAYS days ahead"
    private val todoIssue = Regex("""\bTODO\b[^#]*?\bin \d+\.0\.0 (\(#\d+\))""")

    fun check(path: String, source: String, today: LocalDate): List<Violation> =
        CommentLexer(source).blocks().flatMap { check(path, it, today) }

    private fun check(path: String, block: List<Pair<Int, String>>, today: LocalDate): List<Violation> {
        val violations = mutableListOf<Violation>()
        val allowed = mutableMapOf<Int, String>()
        val lineStarts = mutableListOf<Int>()
        val text = StringBuilder()
        block.forEach { (line, lineText) ->
            val found = marker.find(lineText)
            if (found != null) {
                val rule = allowedRule(found.value, today)
                if (rule == null) violations += Violation(path, line, "lint-allow", found.value, lineText, markerFix)
                else allowed[line] = rule
            }
            if (text.isNotEmpty()) text.append(' ')
            lineStarts += text.length
            text.append(if (found == null) lineText else lineText.substring(0, found.range.first))
        }
        val checked = todoIssue.replace(text) {
            val issue = it.groupValues[1]
            it.value.dropLast(issue.length) + " ".repeat(issue.length)
        }
        rules.forEach { rule ->
            rule.pattern.findAll(checked).forEach { match ->
                val (line, lineText) = block[lineStarts.indexOfLast { it <= match.range.first }]
                if (allowed[line] != rule.id) violations += Violation(path, line, rule.id, match.value, lineText, rule.fix)
            }
        }
        return violations.sortedBy { it.line }
    }

    private fun allowedRule(marker: String, today: LocalDate): String? {
        val match = validMarker.matchEntire(marker) ?: return null
        val until = runCatching { LocalDate.parse(match.groupValues[2]) }.getOrNull() ?: return null
        val rule = match.groupValues[1]
        val inWindow = !until.isBefore(today) && !until.isAfter(today.plusDays(MARKER_MAX_DAYS))
        return rule.takeIf { inWindow && rules.any { it.id == rule } }
    }
}

private class CommentLexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private var lastLineCommentLine = -1
    private val blocks = mutableListOf<MutableList<Pair<Int, String>>>()

    fun blocks(): List<List<Pair<Int, String>>> {
        code(insideTemplate = false)
        return blocks.map { block -> block.filter { it.second.isNotEmpty() } }.filter { it.isNotEmpty() }
    }

    private fun peek(text: String) = source.startsWith(text, pos)

    private fun advance(count: Int = 1) {
        repeat(count) {
            if (pos < source.length && source[pos] == '\n') line++
            pos++
        }
    }

    private fun code(insideTemplate: Boolean) {
        var depth = 0
        while (pos < source.length) {
            when {
                peek("//") -> lineComment()
                peek("/*") -> blockComment()
                peek("\"\"\"") -> rawString()
                peek("\"") -> string()
                peek("'") -> quoted('\'')
                peek("`") -> quoted('`')
                insideTemplate && peek("{") -> { depth++; advance() }
                insideTemplate && peek("}") -> { if (depth == 0) { advance(); return }; depth--; advance() }
                else -> advance()
            }
        }
    }

    private fun lineComment() {
        val end = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
        val entry = line to source.substring(pos, end).trimStart('/').trim()
        if (lastLineCommentLine == line - 1) blocks.last() += entry else blocks += mutableListOf(entry)
        lastLineCommentLine = line
        pos = end
    }

    private fun blockComment() {
        val startLine = line
        val start = pos
        advance(2)
        var depth = 1
        while (pos < source.length && depth > 0) {
            when {
                peek("/*") -> { depth++; advance(2) }
                peek("*/") -> { depth--; advance(2) }
                else -> advance()
            }
        }
        val lines = source.substring(start, pos).removePrefix("/**").removePrefix("/*").removeSuffix("*/").lines()
        blocks += lines.mapIndexedTo(mutableListOf()) { index, text -> (startLine + index) to text.trim().removePrefix("*").trim() }
        lastLineCommentLine = -1
    }

    private fun rawString() {
        advance(3)
        while (pos < source.length && !peek("\"\"\"")) {
            if (peek("\${")) { advance(2); code(insideTemplate = true) } else advance()
        }
        advance(3)
        while (peek("\"")) advance()
    }

    private fun string() {
        advance()
        while (pos < source.length && !peek("\"") && !peek("\n")) {
            when {
                peek("\\") -> advance(2)
                peek("\${") -> { advance(2); code(insideTemplate = true) }
                else -> advance()
            }
        }
        advance()
    }

    private fun quoted(quote: Char) {
        advance()
        while (pos < source.length && source[pos] != quote && source[pos] != '\n') advance(if (peek("\\")) 2 else 1)
        advance()
    }
}
