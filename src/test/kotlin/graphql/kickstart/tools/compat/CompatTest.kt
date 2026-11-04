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

/**
 * Builds every scenario and its invalid variants, and fails when a case that built on the last release throws now.
 * `-Dcompat.capture` records this checkout's results as the capture for its pom version instead.
 */
class CompatTest {
    @Test
    fun `cases that built on the last release still build`() {
        val outcomes = runAll()
        val version = pomVersion()
        if (System.getProperty("compat.capture") != null) {
            val dir = File(CAPTURES, version.toString()).apply { mkdirs() }
            write(File(dir, "cases.tsv"), outcomes)
            File(dir, "accepted.txt").takeUnless { it.exists() }?.writeText("")
            return
        }

        val (release, dir) = CAPTURES.listFiles().orEmpty().mapNotNull { file -> parseVersion(file.name)?.let { it to file } }
            .filter { it.first <= version }.maxByOrNull { it.first }
            ?: return fail("No capture in $CAPTURES at or below $version. Capture the last release as AGENTS.md describes under Releases.")
        val released = read(File(dir, "cases.tsv"))
        val acceptedFile = File(dir, "accepted.txt")
        val accepted = acceptedFile.readLines().filter { it.isNotBlank() }.map { Regex(it.substringBefore('\t')) }
        val major = version.major > release.major

        fun repeatsSeedBreak(key: CaseKey) = key != key.seed && outcomes[key.seed]?.error == outcomes.getValue(key).error
        fun warnedOnRelease(key: CaseKey) =
            (released.getValue(key).warnings - if (key == key.seed) emptySet() else released.getValue(key.seed).warnings.toSet()).isNotEmpty()

        // a variant of a seed that threw on the release can build only by hiding the seed's own error
        val broke = released.keys.filter { released[it.seed]?.built == true && released.getValue(it).built && outcomes[it]?.built == false }
            .filterNot(::repeatsSeedBreak)
            .filterNot { key -> accepted.any { it.containsMatchIn(key.toString()) } }
            .filterNot { major && warnedOnRelease(it) }
        if (broke.isEmpty()) return

        val target = "${release.major + 1}.0.0"
        val cases = broke.take(SHOWN).joinToString("\n") { "  $it\n      ${outcomes.getValue(it).error}" } +
            if (broke.size > SHOWN) "\n  and ${broke.size - SHOWN} more" else ""
        val byOperator = broke.groupingBy { it.operator }.eachCount().toSortedMap()
        fail(
            if (major) {
                """
                |${broke.size} cases built on $release with no WARN of their own and throw now $byOperator.
                |A major may reject only input that the last minor warned about. Log a WARN that names the type and the item in a
                |${release.major}.x release first. A deliberate break that the release notes list goes into $acceptedFile as a
                |regex for the case, a tab, and the issue.
                |$cases
                """.trimMargin()
            } else {
                """
                |${broke.size} cases built on $release and throw now $byOperator.
                |Invalid input that built on the last release keeps building until the next major (AGENTS.md, Compatibility).
                |Instead of throwing, log a WARN that names the type and the item, open a GitHub issue with $target in its title,
                |and leave `// TODO <change> in $target (#N)`. A deliberate break that the release notes list goes into
                |$acceptedFile as a regex for the case, a tab, and the issue.
                |$cases
                """.trimMargin()
            }
        )
    }
}

class Outcome(val error: String?, val warnings: List<String>) {
    val built get() = error == null
}

private val CAPTURES = File("src/test/resources/compat")
private const val SHOWN = 50
private fun runAll(): Map<CaseKey, Outcome> {
    // the tests run in the library's module, which neither reads logback's nor exports the scenarios to kotlin-reflect
    val module = CompatTest::class.java.module
    listOf("ch.qos.logback.classic", "ch.qos.logback.core").forEach { ModuleLayer.boot().findModule(it).ifPresent(module::addReads) }
    ModuleLayer.boot().findModule("kotlin.reflect").ifPresent { module.addExports(Scenario::class.java.packageName, it) }
    val logger = LoggerFactory.getLogger("graphql.kickstart.tools") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    logger.isAdditive = false
    try {
        return SCENARIOS.flatMap(::variants).associate { variant ->
            appender.list.clear()
            val error = try {
                variant.build()
                null
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}: ${normalize(e.message.orEmpty().lineSequence().first())}"
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

private fun pomVersion(): KotlinVersion {
    val pom = File("pom.xml").readText()
    val version = Regex("<artifactId>graphql-java-tools</artifactId>\\s*<version>([^<]+)</version>").find(pom)!!.groupValues[1]
    return parseVersion(version.substringBefore('-'))!!
}

private fun parseVersion(text: String): KotlinVersion? =
    Regex("(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(text)?.destructured?.let { (major, minor, patch) -> KotlinVersion(major.toInt(), minor.toInt(), patch.toInt()) }
