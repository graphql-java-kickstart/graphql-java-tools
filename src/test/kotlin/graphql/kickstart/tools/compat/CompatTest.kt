package graphql.kickstart.tools.compat

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.kickstart.tools.compat.scenarios.SCENARIOS
import graphql.kickstart.tools.compat.scenarios.Scenario
import org.junit.Assert.fail
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.File

class CompatTest {
    @Test
    fun `cases that built on the last release still build`() {
        readLogbackAndExportScenariosToKotlinReflect()
        if (SCENARIOS.isEmpty()) return fail("No scenarios in ${Scenario::class.java.packageName}, so nothing is checked.")
        val variants = SCENARIOS.flatMap(::variants)
        val outcomes = runAll(variants)
        val pom = PomVersion.read()
        val version = pom.version
        if (System.getProperty("compat.capture") != null) {
            if (!pom.isRelease) return fail(
                "pom.xml is at ${pom.text}, so a capture would record this branch instead of a release. Captures are taken from " +
                    "the release tag, as AGENTS.md describes under Releases. To capture a release again with the scenarios you have, " +
                    "run `git checkout vX.Y.Z -- pom.xml src/main` first and `git checkout HEAD -- pom.xml src/main` after."
            )
            val dir = File(CAPTURES, version.toString()).apply { mkdirs() }
            write(File(dir, "cases.tsv"), outcomes)
            File(dir, "accepted.txt").takeUnless { it.exists() }?.writeText("")
            return
        }

        val captures = CAPTURES.listFiles().orEmpty().mapNotNull { file -> parseVersion(file.name)?.let { it to file } }
        val unreleased = captures.filterNot { pom.couldBeReleased(it.first) }
        if (unreleased.isNotEmpty()) return fail(
            "${unreleased.joinToString { it.second.path }} can't hold a release, because pom.xml is at ${pom.text}. A capture made " +
                "on a branch records the branch, so it hides any break. Delete it. Captures are taken from the release tag, as " +
                "AGENTS.md describes under Releases."
        )
        val (release, dir) = captures.maxByOrNull { it.first }
            ?: return fail("No capture in $CAPTURES. Capture the last release as AGENTS.md describes under Releases.")
        val released = read(File(dir, "cases.tsv"))
        if (released.isEmpty()) return fail("$dir/cases.tsv is empty, so nothing is checked. Capture $release as AGENTS.md describes under Releases.")
        val acceptedFile = File(dir, "accepted.txt")
        val major = version.major > release.major
        val warnMustNameOneOfEach = variants.associate { it.key to it.warnMustNameOneOfEach }

        fun builtWithItsSeedOnRelease(key: CaseKey) = released[key.seed]?.built == true && released.getValue(key).built
        fun repeatsSeedBreak(key: CaseKey) = key != key.seed && outcomes[key.seed]?.error == outcomes.getValue(key).error
        fun warnedOnRelease(key: CaseKey): Boolean {
            val ownWarnings = released.getValue(key).warnings - if (key == key.seed) emptySet() else released.getValue(key.seed).warnings.toSet()
            val names = warnMustNameOneOfEach.getValue(key)
            if (names.isEmpty()) return quotedNamesOrWholeMessage(outcomes.getValue(key).error!!).all { name -> ownWarnings.any { it.names(name) } }
            return ownWarnings.any { warning -> names.all { alternatives -> alternatives.any(warning::names) } }
        }
        val protected = released.keys.filter(::builtWithItsSeedOnRelease)
        val accepted = readAccepted(acceptedFile, protected)
        fun isAccepted(key: CaseKey) = accepted.any { it.matches(key) }

        val missing = protected.filterNot(outcomes::containsKey)
        val broken = protected.filter { outcomes[it]?.built == false }.filterNot(::repeatsSeedBreak)
        val stale = accepted.filter { entry -> (missing + broken).none(entry::matches) }
        val broke = broken.filterNot(::isAccepted).filterNot { major && warnedOnRelease(it) }
        val gone = missing.filterNot(::isAccepted)
        if (broke.isEmpty() && gone.isEmpty() && stale.isEmpty()) return

        val target = "${release.major + 1}.0.0"
        val accept = "add a line to $acceptedFile with the case as listed here, a tab, and why, naming its issue (#N) or the release " +
            "notes (\"X.Y.Z release notes\"). Start the case with $PATTERN to give a regex instead."
        val deliberate = "For a deliberate break that the release notes list, $accept"
        val brokeReport = when {
            broke.isEmpty() -> null
            major -> """
                |${broke.size} cases built on $release with no WARN that names them and throw now ${byOperator(broke)}.
                |A major may reject only input that the last minor warned about. Log a WARN that names the type and the item in a
                |${release.major}.x release first. $deliberate
                |${list(broke) { outcomes.getValue(it).error!! }}
                """.trimMargin()
            else -> """
                |${broke.size} cases built on $release and throw now ${byOperator(broke)}.
                |Invalid input that built on the last release keeps building until the next major (AGENTS.md, Compatibility).
                |Instead of throwing, log a WARN that names the type and the item, open a GitHub issue with $target in its title,
                |and leave `// TODO <change> in $target (#N)`. $deliberate
                |${list(broke) { outcomes.getValue(it).error!! }}
                """.trimMargin()
        }
        val goneReport = if (gone.isEmpty()) null else """
            |${gone.size} cases built on $release and aren't generated any more ${byOperator(gone)}, so nothing checks them.
            |A scenario was renamed, removed, or its SDL changed. If that was deliberate, capture $release again with the scenarios
            |you have: `git checkout v$release -- pom.xml src/main`, `mvn -B -ntp clean test -Dtest=CompatTest -Dcompat.capture`,
            |then `git checkout HEAD -- pom.xml src/main`. Otherwise $accept
            |${list(gone)}
            """.trimMargin()
        val staleReport = if (stale.isEmpty()) null else
            "Lines in $acceptedFile that match no case that throws or is gone. Delete each one whose case builds again:\n" +
                stale.joinToString("\n") { "  line ${it.line}: ${it.case}" }
        fail(listOfNotNull(brokeReport, goneReport, staleReport).joinToString("\n\n"))
    }
}

class Outcome(val error: String?, val warnings: List<String>) {
    val built get() = error == null
}

private class Accepted(val line: Int, val case: String) {
    private val pattern = if (case.startsWith(PATTERN)) Regex(case.removePrefix(PATTERN)) else null

    fun matches(key: CaseKey) = pattern?.matches(key.toString()) ?: (case == key.toString())
}

private val CAPTURES = File("src/test/resources/compat")
private const val SHOWN = 50
private const val PATTERN = "re:"

// Fits one operator on one directive or type in one scenario. 14.3.0 applying directives on type extensions, the widest
// deliberate break so far, needs at most 15 per line that way.
private const val MAX_PER_PATTERN = 20
private val REFERENCE = Regex("#\\d+|\\d+\\.\\d+\\.\\d+ release notes")

private fun readAccepted(file: File, protected: List<CaseKey>): List<Accepted> {
    val problems = mutableListOf<String>()
    val accepted = file.readLines().withIndex().filter { it.value.isNotBlank() }.mapNotNull { (index, text) ->
        val case = text.substringBefore('\t')
        val reason = text.substringAfter('\t', "")
        val problem = when {
            !REFERENCE.containsMatchIn(reason) -> "needs a tab and a reason that names an issue (#N) or the release notes (\"X.Y.Z release notes\")"
            case.startsWith(PATTERN) && runCatching { Regex(case.removePrefix(PATTERN)) }.isFailure -> "starts with $PATTERN but isn't a valid regex"
            case.startsWith(PATTERN) -> Accepted(index + 1, case).let { entry ->
                val matched = protected.filter(entry::matches)
                val scenarios = matched.map { it.scenario }.distinct()
                if (scenarios.size <= 1 && matched.size <= MAX_PER_PATTERN) null
                else "accepts ${matched.size} cases in ${scenarios.size} scenarios, and a $PATTERN line may accept at most " +
                    "$MAX_PER_PATTERN, all in one scenario. List the cases, one per line, or one $PATTERN line per scenario"
            }
            else -> null
        }
        problem?.let { problems += "  line ${index + 1} $it: $text" }
        if (problem == null) Accepted(index + 1, case) else null
    }
    if (problems.isNotEmpty()) fail("$file has lines the check can't use:\n${problems.joinToString("\n")}")
    return accepted
}

private fun byOperator(keys: List<CaseKey>) = keys.groupingBy { it.operator }.eachCount().toSortedMap()

private fun list(keys: List<CaseKey>, detail: (CaseKey) -> String? = { null }) =
    keys.take(SHOWN).joinToString("\n") { key -> "  $key" + detail(key)?.let { "\n      $it" }.orEmpty() } +
        if (keys.size > SHOWN) "\n  and ${keys.size - SHOWN} more" else ""

private fun quotedNamesOrWholeMessage(error: String): List<String> =
    Regex("(?<!\\w)'([^']+)'(?!\\w)").findAll(error).map { it.groupValues[1] }.toList().ifEmpty { listOf(error.substringAfter(": ")) }

private fun String.names(name: String) = Regex("(?<!\\w)${Regex.escape(name)}(?!\\w)").containsMatchIn(this)

private fun readLogbackAndExportScenariosToKotlinReflect() {
    val module = CompatTest::class.java.module
    listOf("ch.qos.logback.classic", "ch.qos.logback.core").forEach { ModuleLayer.boot().findModule(it).ifPresent(module::addReads) }
    ModuleLayer.boot().findModule("kotlin.reflect").ifPresent { module.addExports(Scenario::class.java.packageName, it) }
}

private fun runAll(variants: List<Variant>): Map<CaseKey, Outcome> {
    val logger = LoggerFactory.getLogger("graphql.kickstart.tools") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    logger.isAdditive = false
    try {
        return variants.associate { variant ->
            appender.list.clear()
            val error = try {
                variant.build()
                null
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}: ${normalize(e.message.orEmpty())}"
            }
            val warnings = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { normalize(it.formattedMessage) }.sorted()
            variant.key to Outcome(error, warnings)
        }
    } finally {
        logger.isAdditive = true
        logger.detachAppender(appender)
    }
}

private fun normalize(message: String) = message
    .replace(Regex("\\s+"), " ")
    .trim()
    .replace(Regex("jdk\\.proxy\\d+\\.\\\$Proxy\\d+"), "jdk.proxyN.\\\$ProxyN")
    .replace(Regex("\\\$\\\$SpringCGLIB\\\$\\\$\\d+"), "\\\$\\\$SpringCGLIB\\\$\\\$N")
    .replace(Regex("@[0-9a-f]{5,}"), "@HASH")

private fun write(file: File, outcomes: Map<CaseKey, Outcome>) = file.writeText(
    outcomes.entries.joinToString("") { (key, outcome) ->
        (listOf(key.scenario, key.operator, key.snippet, outcome.error ?: "BUILT") + outcome.warnings).joinToString("\t") + "\n"
    }
)

private fun read(file: File): Map<CaseKey, Outcome> = file.readLines().filter { it.isNotEmpty() }.associate { line ->
    val cells = line.split('\t')
    CaseKey(cells[0], cells[1], cells[2]) to Outcome(cells[3].takeUnless { it == "BUILT" }, cells.drop(4))
}

private class PomVersion(val text: String) {
    val version = parseVersion(text.substringBefore('-'))!!
    val isRelease = '-' !in text

    fun couldBeReleased(capture: KotlinVersion) = if (isRelease) capture <= version else capture < version

    companion object {
        fun read() = PomVersion(
            Regex("<artifactId>graphql-java-tools</artifactId>\\s*<version>([^<]+)</version>").find(File("pom.xml").readText())!!.groupValues[1]
        )
    }
}

private fun parseVersion(text: String): KotlinVersion? =
    Regex("(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(text)?.destructured?.let { (major, minor, patch) -> KotlinVersion(major.toInt(), minor.toInt(), patch.toInt()) }
