package graphql.kickstart.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CommentConventionsTest {

    @Test
    fun `comments carry no issue numbers, history or versions`() {
        val sources = listOf(File("src/main"), File("src/test")).asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .sortedBy { it.path }
            .toList()
        assertTrue("no .kt or .java files under ${File("src").absolutePath}, run the test from the project root", sources.isNotEmpty())

        val violations = sources.flatMap { CommentLint.check(it.path, it.readText(), LocalDate.now()) }
        if (violations.isNotEmpty()) {
            val lines = violations.distinctBy { it.path to it.line }.size
            fail(violations.joinToString("\n", "${violations.size} comment convention violation(s) on $lines line(s):\n"))
        }
    }

    private val today = LocalDate.of(2026, 11, 4)

    private fun check(source: String, path: String = "Example.kt") = CommentLint.check(path, source, today)

    private fun rules(source: String, path: String = "Example.kt") = check(source, path).map { it.rule }

    private fun markerFix(source: String) = check(source).single { it.rule == "lint-allow" }.fix

    @Test
    fun `lint flags issue numbers, history and versions in whole comment blocks`() {
        assertEquals(listOf("history"), rules("/* this used to\n * be slow */"))
        assertEquals(listOf("history"), rules("// this used to\n// be slow"))
        assertEquals(listOf("issue-number"), rules("// see #999"))
        assertEquals(listOf("issue-number"), rules("// the enum and directive from issue #823"))
        assertEquals(listOf("issue-number"), rules("// from https://github.com/o/r/issues/424"))
        assertEquals(listOf("issue-number", "history"), rules("// built as the type before #552"))
        assertEquals(listOf("history"), rules("// in the same order as before"))
        assertEquals(listOf("history", "history"), rules("// converted, as it did before this check"))
        assertEquals(listOf("history"), rules("// to keep those schemas building"))
        assertEquals(listOf("history"), rules("// so those schemas keep building"))
        assertEquals(listOf("history"), rules("// this check is no longer needed"))
        assertEquals(listOf("version"), rules("/** @since 15.0 */"))
        assertEquals(listOf("version"), rules("// since 1.2.0 the parser"))
        assertTrue(check("// see #999 as before, since 1.2.0").all { "lint-allow" in it.fix })
    }

    @Test
    fun `lint leaves ordinary prose, code and other languages' syntax alone`() {
        listOf(
            "// returns the list unchanged",
            "// the previously resolved type wins",
            "// no longer than 80 characters",
            "// the result is now cached for the next call",
            "// so the loop keeps working on an empty map",
            "// step #1 builds the dictionary",
            "// the border is #000000 and the text is #fff",
            "// waits since 2.5 seconds have passed",
            "// explains the lint-allow marker syntax for readers",
            "// https://example.com/docs/#123",
            "// TODO reject in 16.0.0 (#999)",
            "val s = \"// see #999\"",
        ).forEach { assertEquals(it, emptyList<String>(), rules(it)) }

        assertEquals(listOf("issue-number"), rules("/* a /* b */\nString s = \"no longer needed\"; // see #13", "Example.java"))
        assertEquals(listOf("issue-number"), rules("String s = \"\"\"\n  \\\"\"\" // #1\n  \"\"\"; // see #2", "Example.java"))
    }

    @Test
    fun `lint honors only valid markers and says why a marker is invalid`() {
        assertEquals(emptyList<String>(), rules("// as before lint-allow history until 2026-12-01 by oryan: domain term"))
        assertEquals(listOf("history"), rules("// as before lint-allow version until 2026-12-01 by oryan: other rule"))
        assertTrue(markerFix("// as before lint-allow history until 2026-11-03 by oryan: stale").contains("expired on 2026-11-03"))
        assertTrue(markerFix("// as before lint-allow history until 2027-06-01 by oryan: too late").contains("more than 90 days"))
        assertTrue(markerFix("// as before lint-allow history until 2026-12-01 by oryan:").contains("no reason"))
        assertTrue(markerFix("// as before lint-allow histroy until 2026-12-01 by oryan: typo").contains("unknown rule `histroy`"))
    }
}

internal object CommentLint {

    class Violation(val path: String, val line: Int, val rule: String, val match: String, val lineText: String, val fix: String) {
        override fun toString() = "$path:$line [$rule] \"$match\" in: $lineText\n    fix: $fix"
    }

    private const val MARKER_MAX_DAYS = 90L
    private const val MARKER_FORM = "lint-allow <rule> until YYYY-MM-DD by <name>: <reason>"
    private const val MOVE_TO_PR = "move the reason into the PR description"

    private class Rule(val id: String, val pattern: Regex, advice: String) {
        val fix = "$advice; for a false positive add `lint-allow $id until YYYY-MM-DD by <name>: <reason>` to the line, " +
            "at most $MARKER_MAX_DAYS days out"
    }

    private val ordinalNouns = listOf("step", "phase", "stage", "item", "case", "rule", "row", "column", "line", "attempt", "round", "part", "pass", "example", "option")

    private val historyPhrases = listOf(
        """\bused to be\b""",
        """\bno longer (?:needed|necessary|required|supported|used|works?|fails?|throws?|returns?|builds?|breaks?|applies|happens|exists?|runs?)\b""",
        """\banymore\b""",
        """\bbefore (?:this|that) (?:change|check|fix|pr|commit)\b""",
        """\bbefore the (?:fix|pr)\b""",
        """\b(?:before|after|until|since) #\d+""",
        """\bas (?:it|they) did\b""",
        """\b(?:as|like|than) before\b""",
        """\bkeeps? (?:those|these|such|existing|old) (?:\w+ )?(?:building|working)\b""",
        """\b(?:those|these|such|existing|old) schemas? (?:still|keeps?) (?:builds?|building|works?|working)\b""",
    )

    private val rules = listOf(
        Rule(
            "issue-number",
            Regex(
                """(?<![\w&/])(?<!\b(?:${ordinalNouns.joinToString("|")}) )#\d{1,5}\b|github\.com/\S+/(?:issues|pull)/\d+""",
                RegexOption.IGNORE_CASE
            ),
            "$MOVE_TO_PR; an issue number belongs only in `// TODO <change> in X.0.0 (#N)`"
        ),
        Rule(
            "history",
            Regex(historyPhrases.joinToString("|"), RegexOption.IGNORE_CASE),
            "say what the code does and why, not how it got here; $MOVE_TO_PR"
        ),
        Rule(
            "version",
            Regex("""@since\b|\bsince (?:v\d+(?:\.\d+)*|version \d+(?:\.\d+)*|\d+\.\d+\.\d+)\b""", RegexOption.IGNORE_CASE),
            "drop the version; history goes in the release notes"
        ),
    )

    private val marker = Regex("""lint-allow [\w-]+ until\b.*""")
    private val markerParts = Regex("""lint-allow ([\w-]+) until (\S+) by [\w-]+: ?(.*)""")
    private val todoIssue = Regex("""\bTODO\b[^#]*?\bin \d+\.0\.0 (\(#\d+\))""")

    fun check(path: String, source: String, today: LocalDate): List<Violation> =
        CommentLexer(source, kotlin = path.endsWith(".kt")).blocks().flatMap { check(path, it, today) }

    private fun check(path: String, block: List<Pair<Int, String>>, today: LocalDate): List<Violation> {
        val violations = mutableListOf<Violation>()
        val allowed = mutableMapOf<Int, String>()
        val lineStarts = mutableListOf<Int>()
        val text = StringBuilder()
        block.forEach { (line, lineText) ->
            val found = marker.find(lineText)
            if (found != null) {
                val parts = markerParts.matchEntire(found.value)
                val problem = markerProblem(parts, today)
                if (problem == null) allowed[line] = parts!!.groupValues[1]
                else violations += Violation(
                    path, line, "lint-allow", found.value, lineText,
                    "$problem; write `$MARKER_FORM` with a rule from ${rules.joinToString { it.id }} and a date at most " +
                        "$MARKER_MAX_DAYS days out, or reword the comment and drop the marker"
                )
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

    private fun markerProblem(parts: MatchResult?, today: LocalDate): String? {
        if (parts == null) return "the marker does not have the form `$MARKER_FORM`"
        val (rule, date, reason) = parts.destructured
        val until = runCatching { LocalDate.parse(date) }.getOrNull()
        return when {
            rules.none { it.id == rule } -> "unknown rule `$rule`"
            until == null -> "`$date` is not a YYYY-MM-DD date"
            until.isBefore(today) -> "the marker expired on $until"
            until.isAfter(today.plusDays(MARKER_MAX_DAYS)) -> "$until is more than $MARKER_MAX_DAYS days out"
            reason.isBlank() -> "the marker has no reason after `by <name>:`"
            else -> null
        }
    }
}

private class CommentLexer(private val source: String, private val kotlin: Boolean) {
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
                kotlin && peek("/*") -> { depth++; advance(2) }
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
            when {
                !kotlin && peek("\\") -> advance(2)
                kotlin && peek("\${") -> { advance(2); code(insideTemplate = true) }
                else -> advance()
            }
        }
        advance(3)
        while (kotlin && peek("\"")) advance()
    }

    private fun string() {
        advance()
        while (pos < source.length && !peek("\"") && !peek("\n")) {
            when {
                peek("\\") -> advance(2)
                kotlin && peek("\${") -> { advance(2); code(insideTemplate = true) }
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
