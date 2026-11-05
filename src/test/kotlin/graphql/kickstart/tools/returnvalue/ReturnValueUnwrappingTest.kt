package graphql.kickstart.tools.returnvalue

import graphql.kickstart.tools.resolver.TypeShape
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.spi.ToolProvider

class ReturnValueUnwrappingTest {

    @Test
    fun `every return type the schema build accepts reaches graphql-java unwrapped`() {
        val dir = Files.createTempDirectory("return-value-unwrapping")
        try {
            val classpath = listOfNotNull(System.getProperty("jdk.module.path"), System.getProperty("java.class.path"))
                .joinToString(File.pathSeparator)
            compile(classpath, dir, copy("Check.java", dir), copy("Fixtures.java", dir))

            val args = System.getProperty("unwrapping.args", "").split(" ").filter { it.isNotBlank() } +
                listOf(
                    "--known", copy("known-findings.txt", dir).toString(),
                    "--container-kinds", TypeShape.containerTypes.joinToString(",") { it.canonicalName }
                )
            val report = ByteArrayOutputStream()
            val passed = URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
                PrintStream(report, true, Charsets.UTF_8).use { out ->
                    loader.loadClass("unwrapcheck.Check")
                        .getMethod("check", Array<String>::class.java, String::class.java, PrintStream::class.java)
                        .invoke(null, args.toTypedArray(), classpath + File.pathSeparator + dir, out) as Boolean
                }
            }
            if (!passed) {
                fail(report.toString(Charsets.UTF_8))
            }
            print(report.toString(Charsets.UTF_8))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun copy(resource: String, dir: Path): Path {
        val target = dir.resolve(resource)
        javaClass.getResourceAsStream("/returnvalue/$resource")!!.use { Files.copy(it, target) }
        return target
    }

    // java.util.spi.ToolProvider is in java.base, so this module needs no access to java.compiler
    private fun compile(classpath: String, dir: Path, vararg sources: Path) {
        val output = ByteArrayOutputStream()
        val javac = ToolProvider.findFirst("javac").orElseThrow()
        val status = PrintStream(output, true, Charsets.UTF_8).use { out ->
            javac.run(out, out, "-d", dir.toString(), "-classpath", classpath, "-nowarn", "-proc:none", *sources.map { it.toString() }.toTypedArray())
        }
        if (status != 0) {
            fail("MODEL ERROR: the check doesn't compile against this checkout:\n" + output.toString(Charsets.UTF_8))
        }
    }
}
