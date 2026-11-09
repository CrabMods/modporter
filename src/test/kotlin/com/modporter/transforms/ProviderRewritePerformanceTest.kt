package com.modporter.transforms

import com.modporter.core.transforms.structural.HolderLookupNbtMethod
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.collectItemStackNbtMethodsNeedingHolderLookup
import com.modporter.core.transforms.structural.migrateProjectItemStackNbtMethods
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderRewritePerformanceTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `a large unrelated project method set does not trigger full source rewrites for absent names`() {
        val source = """
            import net.minecraft.core.HolderLookup;
            public class Sender {
                static final String DATA = "${"x".repeat(100_000)}";
                Target target;
                public void send(HolderLookup.Provider registries) {
                    target.write();
                }
            }
        """.trimIndent()
        val methods = (0 until 2_000).map { HolderLookupNbtMethod("Unrelated$it", "absent$it", 0) }.toSet() +
            HolderLookupNbtMethod("Target", "write", 0)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val result = with(StructuralRefactorPass()) { migrateProjectItemStackNbtMethods(source, methods) }
            assertTrue(result.contains("target.write(registries)"))
        }
    }

    @Test
    fun `provider family call sites stay bounded across dynamic widening and rollback`() {
        val depth = 120
        val source = buildString {
            appendLine("import net.minecraft.core.HolderLookup;")
            appendLine("import net.minecraft.nbt.CompoundTag;")
            appendLine("import net.minecraft.world.item.ItemStack;")
            appendLine(
                """
                interface Writer {
                    CompoundTag write0(CompoundTag tag);
                }
                """.trimIndent()
            )
            appendLine(
                """
                class Link0 implements Writer {
                    ItemStack stack = ItemStack.EMPTY;
                    @Override
                    public CompoundTag write0(CompoundTag tag) {
                        tag.put("stack", stack.save(new CompoundTag()));
                        return tag;
                    }
                }
                """.trimIndent()
            )
            for (index in 1..depth) {
                val previousType = if (index == 1) "Writer" else "Link${index - 1}"
                appendLine(
                    """
                    class Link$index {
                        $previousType previous;
                        public CompoundTag write$index(CompoundTag tag) {
                            return previous.write${index - 1}(tag);
                        }
                    }
                    """.trimIndent()
                )
            }
            appendLine(
                """
                class Root {
                    Link$depth link;
                    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                        return link.write$depth(tag);
                    }
                }
                """.trimIndent()
            )
        }
        val file = tempDir.resolve("src/main/java/example/ProviderGraph.java")
        file.parent.createDirectories()
        file.writeText(source)

        var methods = emptySet<HolderLookupNbtMethod>()
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            methods = with(StructuralRefactorPass()) {
                collectItemStackNbtMethodsNeedingHolderLookup(listOf(file))
            }
        }

        val discovered = methods
            .map { "${it.owner}#${it.methodName}/${it.parameterCount}" }
            .sorted()
        assertTrue(
            methods.any { it.owner == "Writer" && it.methodName == "write0" && it.parameterCount == 1 },
            "missing Writer#write0/1; discovered=$discovered"
        )
        assertTrue(
            methods.any { it.owner == "Link0" && it.methodName == "write0" && it.parameterCount == 1 },
            "missing Link0#write0/1; discovered=$discovered"
        )
        assertEquals(
            (0..depth).mapTo(linkedSetOf()) { "Link$it#write$it" },
            methods.filter { it.owner.startsWith("Link") }
                .mapTo(linkedSetOf()) { "${it.owner}#${it.methodName}" },
            "discovered=$discovered"
        )
    }

    @Test
    fun `cached call sites preserve overload filtering rollback and vacuous all-sites`() {
        val file = tempDir.resolve("src/main/java/example/ProviderEvidence.java")
        file.parent.createDirectories()
        file.writeText(
            """
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class Overloaded {
                ItemStack stack = ItemStack.EMPTY;

                public CompoundTag write(CompoundTag tag) {
                    tag.put("stack", stack.save(new CompoundTag()));
                    return tag;
                }

                public CompoundTag write(String label) {
                    return new CompoundTag();
                }
            }

            class OverloadedRoot {
                Overloaded target;

                public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                    return target.write(tag);
                }
            }

            class OtherOverloadCaller {
                OtherOverloadCaller(Overloaded target) {
                    target.write("label");
                }
            }

            class Stranded {
                ItemStack stack = ItemStack.EMPTY;

                public CompoundTag read(CompoundTag tag) {
                    tag.put("stack", stack.save(new CompoundTag()));
                    return tag;
                }
            }

            class StrandedRoot {
                Stranded target;

                public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                    return target.read(tag);
                }
            }

            class MissingProviderCaller {
                MissingProviderCaller(Stranded target, CompoundTag tag) {
                    target.read(tag);
                }
            }

            class Orphan {
                ItemStack stack = ItemStack.EMPTY;

                private CompoundTag save(CompoundTag tag) {
                    tag.put("stack", stack.save(new CompoundTag()));
                    return tag;
                }
            }
            """.trimIndent()
        )

        val methods = with(StructuralRefactorPass()) {
            collectItemStackNbtMethodsNeedingHolderLookup(listOf(file))
        }

        assertTrue(methods.any {
            it.owner == "Overloaded" &&
                it.methodName == "write" &&
                it.parameterCount == 1 &&
                it.firstParameterType == "CompoundTag"
        })
        assertTrue(methods.none {
            it.owner == "Overloaded" &&
                it.methodName == "write" &&
                it.firstParameterType == "String"
        })
        assertTrue(methods.none { it.owner == "Stranded" && it.methodName == "read" })
        assertTrue(methods.any { it.owner == "Orphan" && it.methodName == "save" })
    }
}
