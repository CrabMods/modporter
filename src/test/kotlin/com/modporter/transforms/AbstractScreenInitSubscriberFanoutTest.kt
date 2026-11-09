package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateAbstractScreenInitSubscriberFanout
import com.modporter.core.pipeline.Change
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbstractScreenInitSubscriberFanoutTest {
    @Test
    fun `abstract screen init subscriber fans out without changing helper body or annotation policy`(
        @TempDir tempDir: Path
    ) {
        val source = """
            package com.example;

            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ScreenEvent;

            class Hooks {
                @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
                public static void onGuiInit(ScreenEvent.Init event) {
                    trace(event.getScreen());
                    event.addListener(widget());
                }

                public static void direct(ScreenEvent.Init event) {
                    onGuiInit(event);
                }
            }
        """.trimIndent()

        val file = tempDir.resolve("Hooks.java")
        val changes = mutableListOf<Change>()
        val pass = StructuralRefactorPass()
        val result = pass.migrateAbstractScreenInitSubscriberFanout(source, file, changes)
        val unit = JavaParser().parse(result).result.orElseThrow()
        val methods = unit.findAll(MethodDeclaration::class.java)

        val helper = methods.single { it.nameAsString == "onGuiInit" }
        assertEquals("ScreenEvent.Init", helper.parameters.single().typeAsString)
        assertTrue(helper.annotations.isEmpty(), result)
        assertTrue(helper.body.orElseThrow().toString().contains("trace(event.getScreen());"), result)
        assertTrue(helper.body.orElseThrow().toString().contains("event.addListener(widget());"), result)

        val pre = methods.single { it.nameAsString == "onGuiInitPre" }
        val post = methods.single { it.nameAsString == "onGuiInitPost" }
        assertEquals("ScreenEvent.Init.Pre", pre.parameters.single().typeAsString)
        assertEquals("ScreenEvent.Init.Post", post.parameters.single().typeAsString)
        for (wrapper in listOf(pre, post)) {
            val annotation = wrapper.annotations.single().toString()
            assertTrue(annotation.contains("priority = EventPriority.HIGH"), annotation)
            assertTrue(annotation.contains("receiveCanceled = true"), annotation)
            assertEquals(
                "onGuiInit((ScreenEvent.Init) event);",
                wrapper.body.orElseThrow().statements.single().toString()
            )
        }
        assertEquals(
            "onGuiInit(event);",
            methods.single { it.nameAsString == "direct" }
                .body.orElseThrow().statements.single().toString()
        )
        assertEquals(1, changes.size)
        assertEquals(
            "struct-abstract-screen-init-subscriber-fanout",
            changes.single().ruleId
        )
        assertEquals(
            result,
            pass.migrateAbstractScreenInitSubscriberFanout(result, file, mutableListOf())
        )
    }

    @Test
    fun `generated wrappers preserve legacy pre post dispatch exactly`(@TempDir tempDir: Path) {
        val source = """
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ScreenEvent;

            class Hooks {
                static final java.util.List<String> TRACE = new java.util.ArrayList<>();

                @SubscribeEvent
                public static void onGuiInit(ScreenEvent.Init event) {
                    TRACE.add(event.getClass().getSimpleName());
                }
            }
        """.trimIndent()
        val result = StructuralRefactorPass().migrateAbstractScreenInitSubscriberFanout(
            source,
            tempDir.resolve("Hooks.java"),
            mutableListOf()
        )
        assertTrue(result.contains("onGuiInitPre(ScreenEvent.Init.Pre event)"), result)
        assertTrue(result.contains("onGuiInitPost(ScreenEvent.Init.Post event)"), result)
        assertTrue(result.contains("onGuiInit((ScreenEvent.Init) event);"), result)
    }

    @Test
    fun `lookalikes phase handlers and collisions are refused`(@TempDir tempDir: Path) {
        val pass = StructuralRefactorPass()
        val lookalike = """
            class ScreenEvent { static class Init {} }
            @interface SubscribeEvent {}
            class Hooks {
                @SubscribeEvent static void onGuiInit(ScreenEvent.Init event) {}
            }
        """.trimIndent()
        assertEquals(
            lookalike,
            pass.migrateAbstractScreenInitSubscriberFanout(
                lookalike,
                tempDir.resolve("Lookalike.java"),
                mutableListOf()
            )
        )

        val alreadyConcrete = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ScreenEvent;
            class Hooks {
                @SubscribeEvent static void onGuiInit(ScreenEvent.Init.Post event) {}
            }
        """.trimIndent()
        assertEquals(
            alreadyConcrete,
            pass.migrateAbstractScreenInitSubscriberFanout(
                alreadyConcrete,
                tempDir.resolve("Concrete.java"),
                mutableListOf()
            )
        )

        val collision = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ScreenEvent;
            class Hooks {
                @SubscribeEvent static void onGuiInit(ScreenEvent.Init event) {}
                static void onGuiInitPre(ScreenEvent.Init.Pre event) {}
            }
        """.trimIndent()
        assertEquals(
            collision,
            pass.migrateAbstractScreenInitSubscriberFanout(
                collision,
                tempDir.resolve("Collision.java"),
                mutableListOf()
            )
        )
    }
}
