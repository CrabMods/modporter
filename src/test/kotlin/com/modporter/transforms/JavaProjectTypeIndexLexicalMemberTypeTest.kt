package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaProjectTypeIndexLexicalMemberTypeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nearest lexical member type selects the exact LayerPattern Builder`() {
        val source = """
            package sample;

            import external.Builder;
            import external.ExternalBase;
            import java.util.function.Consumer;

            class LayerPattern {
                static Builder builder() {
                    return new Builder();
                }

                static class Builder {
                    Builder inNether() {
                        return this;
                    }

                    Builder layer(Consumer<Layer.Builder> builder) {
                        return this;
                    }
                }

                static class Layer {
                    static class Builder {
                        Builder block() {
                            return new Builder();
                        }
                    }
                }
            }

            class Outside extends ExternalBase {
                Builder imported(Builder value) {
                    return value;
                }
            }
        """.trimIndent()
        val file = write("sample/LayerPattern.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val outerFactory = method(unit, "builder")
        val outerSelf = method(unit, "inNether")
        val qualifiedInner = method(unit, "layer")
        val innerSelf = method(unit, "block")
        val imported = method(unit, "imported")

        assertEquals(OUTER_BUILDER, index.declaredType(outerFactory.type, outerFactory))
        assertEquals(
            OUTER_BUILDER,
            index.expressionType(outerFactory.findFirst(ObjectCreationExpr::class.java).orElseThrow(), outerFactory)
        )
        assertEquals(OUTER_BUILDER, index.declaredType(outerSelf.type, outerSelf))
        assertEquals(
            "java.util.function.Consumer" to listOf(INNER_BUILDER),
            index.declaredTypeWithArguments(qualifiedInner.parameters.single().type, qualifiedInner)
        )
        assertEquals(INNER_BUILDER, index.declaredType(innerSelf.type, innerSelf))
        assertEquals(
            INNER_BUILDER,
            index.expressionType(innerSelf.findFirst(ObjectCreationExpr::class.java).orElseThrow(), innerSelf)
        )
        assertEquals("external.Builder", index.declaredType(imported.type, imported))
        assertEquals("external.Builder", index.declaredType(imported.parameters.single().type, imported))
        assertEquals(source, file.readText())
    }

    @Test
    fun `nested member types outside the lexical owner remain invisible`() {
        val source = """
            package sample;

            class LayerPattern {
                static class Builder {}
                static class Layer {
                    static class Builder {}
                }
            }

            class Outside {
                Builder unresolved(Builder value) {
                    return value;
                }
            }
        """.trimIndent()
        val file = write("sample/InvisibleBuilders.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val unresolved = method(unit, "unresolved")

        assertNull(index.declaredType(unresolved.type, unresolved))
        assertNull(index.declaredType(unresolved.parameters.single().type, unresolved))
        assertEquals(source, file.readText())
    }

    @Test
    fun `inherited member types shadow enclosing types and retain their declaring owner`() {
        val source = """
            package sample;

            class Outer {
                static class Builder {}

                static class Child extends Base {
                    Builder inherited() {
                        return null;
                    }

                    Child.Builder qualifiedInherited() {
                        return null;
                    }

                    Builder.Nested inheritedNested() {
                        return null;
                    }
                }
            }

            class Base {
                static class Builder {
                    static class Nested {}
                }
            }
        """.trimIndent()
        val file = write("sample/InheritedBuilders.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)

        assertEquals("sample.Base.Builder", index.declaredType(method(unit, "inherited").type, method(unit, "inherited")))
        assertEquals(
            "sample.Base.Builder",
            index.declaredType(method(unit, "qualifiedInherited").type, method(unit, "qualifiedInherited"))
        )
        assertEquals(
            "sample.Base.Builder.Nested",
            index.declaredType(method(unit, "inheritedNested").type, method(unit, "inheritedNested"))
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `private superclass member types do not hide enclosing lexical types`() {
        val source = """
            package sample;

            class PrivateOuter {
                static class Builder {}

                static class Child extends PrivateBase {
                    Builder lexical() {
                        return null;
                    }
                }
            }

            class PrivateBase {
                private static class Builder {}
            }
        """.trimIndent()
        val file = write("sample/PrivateInheritedBuilder.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val lexical = method(unit, "lexical")

        assertEquals("sample.PrivateOuter.Builder", index.declaredType(lexical.type, lexical))
        assertEquals(source, file.readText())
    }

    @Test
    fun `conflicting inherited interface member types fail closed`() {
        val source = """
            package sample;

            interface Left {
                class Builder {}
            }

            interface Right {
                class Builder {}
            }

            class AmbiguousChild implements Left, Right {
                Builder conflict() {
                    return null;
                }
            }
        """.trimIndent()
        val file = write("sample/AmbiguousInheritedBuilders.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val conflict = method(unit, "conflict")

        val error = assertFailsWith<IllegalStateException> {
            index.declaredType(conflict.type, conflict)
        }
        assertTrue(error.message.orEmpty().contains("Ambiguous inherited member type 'Builder'"))
        assertEquals(source, file.readText())
    }

    @Test
    fun `separate direct interface branches remain ambiguous when one extends the other`() {
        val source = """
            package sample;

            interface Parent {
                class Builder {}
            }

            interface Child extends Parent {
                class Builder {}
            }

            class AmbiguousRelatedChild implements Parent, Child {
                Builder conflict() {
                    return null;
                }
            }

            class ChildOnly implements Child {
                Builder inherited() {
                    return null;
                }
            }
        """.trimIndent()
        val file = write("sample/RelatedInheritedBuilders.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val conflict = method(unit, "conflict")
        val inherited = method(unit, "inherited")

        val error = assertFailsWith<IllegalStateException> {
            index.declaredType(conflict.type, conflict)
        }
        assertTrue(error.message.orEmpty().contains("Ambiguous inherited member type 'Builder'"))
        assertEquals("sample.Child.Builder", index.declaredType(inherited.type, inherited))
        assertEquals(source, file.readText())
    }

    @Test
    fun `protected member types stay accessible throughout a subclass nested body`() {
        val baseSource = """
            package p;

            public class Base {
                protected static class Builder {
                    public static class Nested {}
                }
            }
        """.trimIndent()
        val childSource = """
            package q;

            import p.Base;

            class Child extends Base {
                Builder simple() {
                    return null;
                }

                Base.Builder baseQualified() {
                    return null;
                }

                Child.Builder childQualified() {
                    return null;
                }

                static class NestedUse {
                    Builder nestedSimple() {
                        return null;
                    }

                    Base.Builder nestedBaseQualified() {
                        return null;
                    }

                    Child.Builder nestedChildQualified() {
                        return null;
                    }

                    Child.Builder.Nested nestedPath() {
                        return null;
                    }
                }
            }
        """.trimIndent()
        val baseFile = write("p/Base.java", baseSource)
        val childFile = write("q/Child.java", childSource)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(childFile)

        listOf(
            "simple",
            "baseQualified",
            "childQualified",
            "nestedSimple",
            "nestedBaseQualified",
            "nestedChildQualified"
        ).forEach { name ->
            val declaration = method(unit, name)
            assertEquals("p.Base.Builder", index.declaredType(declaration.type, declaration), name)
        }
        val nestedPath = method(unit, "nestedPath")
        assertEquals("p.Base.Builder.Nested", index.declaredType(nestedPath.type, nestedPath))
        assertEquals(baseSource, baseFile.readText())
        assertEquals(childSource, childFile.readText())
    }

    @Test
    fun `implicit Runnable parent preserves exact imported field receiver lookup`() {
        val source = """
            package sample;

            import external.Codec;

            class Handler implements Runnable {
                private Codec codec;

                @Override
                public void run() {
                    codec.serializeNBT();
                }
            }
        """.trimIndent()
        val file = write("sample/Handler.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val run = method(unit, "run")
        val serialize = run.findAll(MethodCallExpr::class.java)
            .single { it.nameAsString == "serializeNBT" }

        assertEquals("external.Codec", index.expressionType(serialize.scope.orElseThrow(), serialize))
        assertEquals(source, file.readText())
    }

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }

    private companion object {
        const val OUTER_BUILDER = "sample.LayerPattern.Builder"
        const val INNER_BUILDER = "sample.LayerPattern.Layer.Builder"
    }
}