package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedRecordProviderMethodContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private val computerUtilContracts = setOf(
        HolderLookupNbtMethod(
            "ComputerUtil",
            "deepEquals",
            2,
            2,
            listOf("java.lang.Object", "java.lang.Object")
        ),
        HolderLookupNbtMethod(
            "ComputerUtil",
            "matchList",
            2,
            2,
            listOf(
                "example.ComputerUtil.Collection",
                "example.ComputerUtil.Collection"
            )
        ),
        HolderLookupNbtMethod(
            "ComputerUtil",
            "matchMap",
            2,
            2,
            listOf(
                "example.ComputerUtil.Collection",
                "example.ComputerUtil.Collection"
            )
        )
    )

    private fun migrate(source: String, methods: Set<HolderLookupNbtMethod>): String =
        with(StructuralRefactorPass()) {
            migrateProjectItemStackNbtMethods(source, methods)
        }

    private fun parse(source: String): CompilationUnit {
        val parsed = parser.parse(source)
        assertTrue(parsed.result.isPresent, parsed.problems.joinToString("\n"))
        return parsed.result.orElseThrow()
    }

    private fun computerUtilSource(accessors: Boolean): String {
        fun component(owner: String, name: String): String =
            if (accessors) "$owner.$name()" else "$owner.$name"

        val fMode = component("f", "mode")
        val fList = component("f", "list")
        val iList = component("i", "list")
        val fMap = component("f", "map")
        val iMap = component("i", "map")
        return """
            package example;

            import java.util.Iterator;
            import java.util.List;
            import java.util.Map;

            final class ComputerUtil {
                private static boolean deepEquals(Object fVal, Object iVal) {
                    Collection fColl = Collection.of(fVal);
                    Collection iColl = Collection.of(iVal);
                    if ($fMode == MatchMode.LIST)
                        return matchList(fColl, iColl);
                    return matchMap(fColl, iColl);
                }

                private static boolean matchList(Collection f, Collection i) {
                    for (int k = 0; k < $fList.size(); k++) {
                        if (!deepEquals($fList.get(k), $iList.get(k)))
                            return false;
                    }
                    outer: for (Object fVal : $fList) {
                        for (Iterator<?> it = $iList.iterator(); it.hasNext();) {
                            Object iVal = it.next();
                            if (deepEquals(fVal, iVal))
                                continue outer;
                        }
                        return false;
                    }
                    outer: for (Object iVal : $iList) {
                        for (Iterator<?> it = $fList.iterator(); it.hasNext();) {
                            Object fVal = it.next();
                            if (deepEquals(fVal, iVal))
                                continue outer;
                        }
                        return false;
                    }
                    return true;
                }

                private static boolean matchMap(Collection f, Collection i) {
                    for (var e : $fMap.entrySet()) {
                        if (!deepEquals(e.getValue(), $iMap.get(e.getKey())))
                            return false;
                    }
                    return true;
                }

                private enum MatchMode { LIST, MAP }

                private record Collection(MatchMode mode, List<?> list, Map<?, ?> map) {
                    private static Collection of(Object value) {
                        if (value instanceof Map<?, ?> map)
                            return new Collection(MatchMode.MAP, List.of(), map);
                        if (value instanceof List<?> list)
                            return new Collection(MatchMode.LIST, list, Map.of());
                        return new Collection(MatchMode.LIST, List.of(value), Map.of());
                    }
                }
            }
        """.trimIndent() + "\n"
    }

    @Test
    fun `reduced ComputerUtil nested record contracts migrate direct fields and accessors atomically`() {
        for (accessors in listOf(false, true)) {
            val style = if (accessors) "accessors" else "fields"
            val source = computerUtilSource(accessors)
            val file = tempDir.resolve("ComputerUtil-$style.java")
            file.writeText(source)

            val dryResult = migrate(file.readText(), computerUtilContracts)
            assertEquals(source, file.readText(), "dry migration changed $style input")
            val unit = parse(dryResult)

            for (name in listOf("deepEquals", "matchList", "matchMap")) {
                val declaration = unit.findAll(MethodDeclaration::class.java)
                    .single { it.nameAsString == name }
                assertEquals(3, declaration.parameters.size, "$name in $style\n$dryResult")
                assertEquals(
                    "HolderLookup.Provider",
                    declaration.parameters.last().typeAsString,
                    "$name in $style\n$dryResult"
                )
            }
            for (name in listOf("deepEquals", "matchList", "matchMap")) {
                val calls = unit.findAll(MethodCallExpr::class.java)
                    .filter { it.nameAsString == name }
                assertTrue(calls.isNotEmpty(), "$name calls missing in $style\n$dryResult")
                assertTrue(
                    calls.all { call ->
                        call.arguments.size == 3 &&
                            call.arguments.last().toString() == "registries"
                    },
                    "$name calls did not share the declaration contract in $style\n$dryResult"
                )
            }
            val expectedLoop = if (accessors) {
                "for (var e : f.map().entrySet())"
            } else {
                "for (var e : f.map.entrySet())"
            }
            assertTrue(dryResult.contains(expectedLoop), dryResult)
            assertFalse(dryResult.contains("for (Object e :"), dryResult)

            file.writeText(dryResult)
            assertEquals(dryResult, file.readText())
            assertEquals(dryResult, migrate(file.readText(), computerUtilContracts))
        }
    }

    @Test
    fun `same named nested types keep their enclosing owner for declarations and calls`() {
        val source = """
            package example;

            import net.minecraft.core.HolderLookup;

            class OuterOne {
                record Collection(Object value) {}

                static void invoke(HolderLookup.Provider registries, Collection value) {
                    Dispatcher.route(value);
                }
            }

            class OuterTwo {
                record Collection(Object value) {}

                static void invoke(HolderLookup.Provider registries, Collection value) {
                    Dispatcher.route(value);
                }
            }

            class Dispatcher {
                static void route(OuterOne.Collection value) {}
                static void route(OuterTwo.Collection value) {}
            }
        """.trimIndent() + "\n"
        val contracts = setOf(
            HolderLookupNbtMethod(
                "Dispatcher",
                "route",
                1,
                1,
                listOf("example.OuterOne.Collection"),
                requiresTypedCallResolution = true
            ),
            HolderLookupNbtMethod(
                "Dispatcher",
                "route",
                1,
                0,
                listOf("example.OuterTwo.Collection"),
                requiresTypedCallResolution = true
            )
        )

        val migrated = migrate(source, contracts)
        parse(migrated)
        assertTrue(
            migrated.contains(
                "static void route(OuterOne.Collection value, HolderLookup.Provider registries)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "static void route(HolderLookup.Provider registries, OuterTwo.Collection value)"
            ),
            migrated
        )
        assertTrue(migrated.contains("Dispatcher.route(value, registries);"), migrated)
        assertTrue(migrated.contains("Dispatcher.route(registries, value);"), migrated)
        assertEquals(migrated, migrate(migrated, contracts))
    }

    @Test
    fun `known nested type mismatch cannot use the singleton typed fallback`() {
        val source = """
            package example;

            import net.minecraft.core.HolderLookup;

            class OuterOne {
                record Collection(Object value) {}
            }

            class OuterTwo {
                record Collection(Object value) {}
            }

            class Dispatcher {
                static void route(OuterTwo.Collection value) {}

                static void invoke(HolderLookup.Provider registries, OuterTwo.Collection value) {
                    route(value);
                }
            }
        """.trimIndent() + "\n"
        val contract = HolderLookupNbtMethod(
            "Dispatcher",
            "route",
            1,
            1,
            listOf("example.OuterOne.Collection")
        )

        val migrated = migrate(source, setOf(contract))
        parse(migrated)
        assertEquals(source, migrated)
        assertFalse(migrated.contains("route(value, registries)"), migrated)
    }

    @Test
    fun `ambiguous typed contract failure leaves the apply target unchanged`() {
        val source = """
            package example;

            class AtomicTarget {
                static void route(Object value) {}
            }
        """.trimIndent() + "\n"
        val file = tempDir.resolve("AtomicTarget.java")
        file.writeText(source)
        val contracts = setOf(
            HolderLookupNbtMethod(
                "AtomicTarget",
                "route",
                1,
                0,
                listOf("java.lang.Object")
            ),
            HolderLookupNbtMethod(
                "AtomicTarget",
                "route",
                1,
                1,
                listOf("java.lang.Object")
            )
        )

        assertFailsWith<IllegalStateException> {
            migrate(file.readText(), contracts)
        }
        assertEquals(source, file.readText())
        parse(file.readText())
    }
}
