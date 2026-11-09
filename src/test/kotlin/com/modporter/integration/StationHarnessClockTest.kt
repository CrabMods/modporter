package com.modporter.integration

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationHarnessClockTest {
    @Test
    fun `fixture and ordered scenario own separate exact timeout windows on the same clock`(@TempDir root: Path) {
        val source = CreateStationMapRuntimeHarnessSource.source("example.benchmark", "create")
        val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
            .parse(source).result.orElseThrow()
        val starts = unit.findAll(AssignExpr::class.java).filter { it.target.toString() == "stageStartedTick" }
        assertEquals(4, starts.size)
        assertTrue(starts.count { it.value.toString() == "currentTickLocked()" } == 3)
        assertTrue(starts.single { it.value.toString() != "currentTickLocked()" }.value.toString() == "scenarioOwnedTick")
        assertTrue(source.contains("Runtime fingerprint prepared before world startup"))
        assertTrue(source.contains("Fixture ready; waiting for ordered scenario ownership"))
        assertTrue(source.contains("Ordered station scenario acquired"))
        assertTrue(source.contains("scenarioOwnedTick >= 0L"))
        assertTrue(source.contains("if (current > GLOBAL_TIMEOUT_TICKS)"))
        val prerequisite = source.indexOf("!Boolean.getBoolean(BROWSE_COMPLETED_PROPERTY)")
        assertTrue(source.lastIndexOf("fixtureReadyTick = currentTickLocked()", prerequisite) >= 0)
        assertTrue(source.indexOf("scenarioOwnedTick = currentTickLocked()", prerequisite) > prerequisite)
        val fields = unit.findAll(FieldDeclaration::class.java).filter {
            it.variables.any { v ->
                v.nameAsString in setOf(
                    "GLOBAL_TIMEOUT_TICKS",
                    "STAGE_TIMEOUT_TICKS",
                    "fixtureReadyTick",
                    "scenarioOwnedTick"
                )
            }
        }.joinToString("\n")
        val methods = listOf("currentTickLocked", "setStageLocked", "checkTimeoutLocked").joinToString("\n") { name ->
            unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }.toString()
        }
        val stages = unit.findAll(EnumDeclaration::class.java).single { it.nameAsString == "Stage" }
        val file = root.resolve("StationClockProbe.java")
        file.writeText("""
            public class StationClockProbe {
                $fields
                $stages
                private static long clientTicks, serverTicks, stageStartedTick;
                private static boolean initialized = true;
                private static Stage stage;
                private static String failure;
                private static void failLocked(String value) { failure = value; }
                $methods
                public static long begin(long client, long server) {
                    clientTicks = client; serverTicks = server; failure = null;
                    fixtureReadyTick = -1L; scenarioOwnedTick = -1L;
                    setStageLocked(Stage.WAIT_PREREQUISITES);
                    return stageStartedTick;
                }
                public static long markFixtureReady() {
                    fixtureReadyTick = currentTickLocked();
                    return fixtureReadyTick;
                }
                public static long acquireScenario() {
                    scenarioOwnedTick = currentTickLocked();
                    stageStartedTick = scenarioOwnedTick;
                    return scenarioOwnedTick;
                }
                public static String advance(long client, long server) {
                    clientTicks = client; serverTicks = server;
                    checkTimeoutLocked(); return failure;
                }
            }
        """.trimIndent())
        val output = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, errors, errors,
            "-d", output.toString(), file.toString()), errors.toString())
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
            val type = loader.loadClass("StationClockProbe")
            val begin = type.getMethod("begin", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val markFixtureReady = type.getMethod("markFixtureReady")
            val acquireScenario = type.getMethod("acquireScenario")
            val advance = type.getMethod("advance", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)

            // The original absolute global bound remains active before fixture readiness.
            assertEquals(300L, begin.invoke(null, 1L, 300L))
            assertEquals(null, advance.invoke(null, 1L, 2400L))
            assertTrue(advance.invoke(null, 1L, 2401L).toString().contains("global timeout"))

            // Ordered predecessor scenarios may consume more than 400 ticks while
            // the unchanged absolute 2400-tick global bound still applies.
            begin.invoke(null, 1L, 300L)
            assertEquals(300L, markFixtureReady.invoke(null))
            assertEquals(null, advance.invoke(null, 1L, 702L))
            assertEquals(702L, acquireScenario.invoke(null))
            assertEquals(null, advance.invoke(null, 1L, 1102L))
            assertTrue(advance.invoke(null, 1L, 1103L).toString().contains("stage timed out"))

            // The max(client, server) clock preserves the exact global boundary.
            begin.invoke(null, 300L, 1L)
            assertEquals(null, advance.invoke(null, 2400L, 1L))
            assertTrue(advance.invoke(null, 2401L, 1L).toString().contains("global timeout"))
        }
    }
}
