package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TickEventPhaseHandlersTest {
    @Test
    fun `single early phase guard selects correct native subscription`() {
        val source = """
            import net.neoforged.neoforge.event.tick.PlayerTickEvent;
            import net.neoforged.neoforge.event.TickEvent;
            class Hooks {
                void post(PlayerTickEvent event) {
                    if (event.phase == TickEvent.Phase.START)
                        return;
                    observe(event.getEntity());
                }
                void pre(PlayerTickEvent evt) {
                    if (TickEvent.Phase.START != evt.phase) { return; }
                    observe(evt.getEntity());
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val result = pass.migrateUnsplitTickEventHandlers(source)
        assertTrue(result.contains("PlayerTickEvent.Post event"), result)
        assertTrue(result.contains("PlayerTickEvent.Pre evt"), result)
        assertFalse(result.contains(".phase"), result)
        assertTrue(result.contains("observe(event.getEntity());"), result)
        assertEquals(result, pass.migrateUnsplitTickEventHandlers(result))
    }

    @Test
    fun `unknown event and multiple phase observations remain unchanged`() {
        val pass = StructuralRefactorPass()
        val unknown = "class Hooks { void tick(PlayerTickEvent event) { if (event.phase == TickEvent.Phase.START) return; observe(); } }"
        assertEquals(unknown, pass.migrateUnsplitTickEventHandlers(unknown))
        val both = """
            import net.neoforged.neoforge.event.tick.PlayerTickEvent;
            class Hooks { void tick(PlayerTickEvent event) {
                if (event.phase == TickEvent.Phase.START) return;
                observe(event.phase);
            } }
        """.trimIndent()
        assertEquals(both, pass.migrateUnsplitTickEventHandlers(both))
    }

    @Test
    fun `create shaped client handler splits into faithful pre and post subscriptions`(@TempDir tempDir: Path) {
        val source = """
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.event.TickEvent.Phase;
            class Hooks {
                @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
                public static void onTick(ClientTickEvent.Post event) {
                    if (!isGameActive())
                        return;
                    int world = level();
                    if (event.phase == Phase.START) {
                        linkedController();
                        controls();
                        airSounds();
                        return;
                    }
                    soundScapes();
                    postTail(world);
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val result = pass.migrateUnsplitTickEventHandlers(source)
        val unit = JavaParser().parse(result).result.orElseThrow()
        val handlers = unit.findAll(MethodDeclaration::class.java).filter { it.nameAsString == "onTick" }
        assertEquals(2, handlers.size, result)
        val pre = handlers.single { it.parameters[0].typeAsString.endsWith("ClientTickEvent.Pre") }
        val post = handlers.single { it.parameters[0].typeAsString.endsWith("ClientTickEvent.Post") }

        val preCalls = pre.findAll(MethodCallExpr::class.java).map { it.nameAsString }
        val postCalls = post.findAll(MethodCallExpr::class.java).map { it.nameAsString }
        assertEquals(listOf("isGameActive", "level", "linkedController", "controls", "airSounds"), preCalls)
        assertEquals(listOf("isGameActive", "level", "soundScapes", "postTail"), postCalls)
        val legacyTraces = executeTickFixture(source, tempDir.resolve("legacy"), split = false)
        val nativeTraces = executeTickFixture(result, tempDir.resolve("native"), split = true)
        assertEquals(listOf("isGameActive,isGameActive,", "isGameActive,level,linkedController,controls,airSounds,isGameActive,level,soundScapes,postTail:42,"), legacyTraces)
        assertEquals(legacyTraces, nativeTraces)
        for (handler in handlers) {
            val annotation = handler.annotations.single().toString()
            assertTrue(annotation.contains("priority = EventPriority.HIGH"), annotation)
            assertTrue(annotation.contains("receiveCanceled = true"), annotation)
        }
        assertFalse(result.contains(".phase"), result)
        assertEquals(result, pass.migrateUnsplitTickEventHandlers(result))
    }

    @Test
    fun `split phase cleanup is scoped to the typed method even when parameter names match`() {
        val source = """
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.client.event.RenderFrameEvent;
            import net.neoforged.neoforge.event.TickEvent;
            class Hooks {
                void client(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.START) {
                        preAction();
                        return;
                    }
                    postAction();
                }
                void render(RenderFrameEvent.Pre event) {
                    if (event.phase == TickEvent.Phase.START) {
                        renderAction();
                    }
                }
                void fake(FakeClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.START) {
                        fakeAction();
                    }
                }
                String documentation = "if (event.phase == TickEvent.Phase.START) {";
            }
        """.trimIndent()

        val result = StructuralRefactorPass().removeLegacyTickPhaseChecks(source)
        val client = JavaParser().parse(result).result.orElseThrow()
            .findAll(MethodDeclaration::class.java).single { it.nameAsString == "client" }.toString()
        val render = JavaParser().parse(result).result.orElseThrow()
            .findAll(MethodDeclaration::class.java).single { it.nameAsString == "render" }.toString()
        val fake = JavaParser().parse(result).result.orElseThrow()
            .findAll(MethodDeclaration::class.java).single { it.nameAsString == "fake" }.toString()
        assertTrue(client.contains("event.phase == TickEvent.Phase.START"), client)
        assertTrue(client.contains("preAction();"), client)
        assertFalse(render.contains("event.phase"), render)
        assertTrue(render.contains("renderAction();"), render)
        assertTrue(fake.contains("event.phase == TickEvent.Phase.START"), fake)
        assertTrue(result.contains("\"if (event.phase == TickEvent.Phase.START) {\""), result)
    }

    @Test
    fun `split phase guard is removed after the text pass replaced the legacy import`() {
        val source = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.event.tick.ServerTickEvent;
            class Hooks {
                @SubscribeEvent
                public static void onClientTick(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.END) {
                        runGate();
                    }
                }
            }
        """.trimIndent()

        val result = StructuralRefactorPass().removeLegacyTickPhaseChecks(source)

        assertFalse(result.contains("event.phase"), result)
        assertTrue(result.contains("runGate();"), result)
        JavaParser().parse(result).result.orElseThrow()
    }

    @Test
    fun `split phase guard stays when a TickEvent is imported or declared elsewhere`() {
        val imported = """
            import com.example.TickEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            class Hooks {
                void onClientTick(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.END) { runGate(); }
                }
            }
        """.trimIndent()
        assertEquals(imported, StructuralRefactorPass().removeLegacyTickPhaseChecks(imported))

        val declared = """
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            class TickEvent { enum Phase { START, END } }
            class Hooks {
                void onClientTick(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.END) { runGate(); }
                }
            }
        """.trimIndent()
        assertEquals(declared, StructuralRefactorPass().removeLegacyTickPhaseChecks(declared))
    }

    @Test
    fun `mixed split rejects lookalike owners unbound phases and escaping event values`() {
        val pass = StructuralRefactorPass()
        val fakeOwner = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.TickEvent.Phase;
            class Hooks {
                @SubscribeEvent void tick(FakeClientTickEvent.Post event) {
                    prefix();
                    if (event.phase == Phase.START) { pre(); return; }
                    post();
                }
            }
        """.trimIndent()
        assertEquals(fakeOwner, pass.migrateUnsplitTickEventHandlers(fakeOwner))

        val fakePhase = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            class TickEvent { enum Phase { START, END } }
            class Hooks {
                String documentation = ${"\"\"\""}
                    import net.neoforged.neoforge.event.TickEvent;
                    ${"\"\"\""};
                @SubscribeEvent void tick(ClientTickEvent.Post event) {
                    prefix();
                    if (event.phase == TickEvent.Phase.START) { pre(); return; }
                    post();
                }
            }
        """.trimIndent()
        assertEquals(fakePhase, pass.migrateUnsplitTickEventHandlers(fakePhase))

        val escaping = """
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.event.TickEvent.Phase;
            class Hooks {
                @SubscribeEvent void tick(ClientTickEvent.Post event) {
                    prefix(event);
                    if (event.phase == Phase.START) { pre(); return; }
                    post();
                }
            }
        """.trimIndent()
        assertEquals(escaping, pass.migrateUnsplitTickEventHandlers(escaping))
    }

    private fun executeTickFixture(source: String, root: Path, split: Boolean): List<String> {
        Files.createDirectories(root)
        val members = """
            static boolean active;
            static final StringBuilder trace = new StringBuilder();
            static boolean isGameActive() { trace.append("isGameActive,"); return active; }
            static int level() { trace.append("level,"); return 42; }
            static void linkedController() { trace.append("linkedController,"); }
            static void controls() { trace.append("controls,"); }
            static void airSounds() { trace.append("airSounds,"); }
            static void soundScapes() { trace.append("soundScapes,"); }
            static void postTail(int world) { trace.append("postTail:").append(world).append(','); }
        """.trimIndent()
        val fixture = source.substringBeforeLast('}') + members + "\n}"
        val sources = linkedMapOf(
            "Hooks.java" to fixture,
            "net/neoforged/neoforge/event/TickEvent.java" to "package net.neoforged.neoforge.event; public class TickEvent { public enum Phase { START, END } }",
            "net/neoforged/bus/api/EventPriority.java" to "package net.neoforged.bus.api; public enum EventPriority { NORMAL, HIGH }",
            "net/neoforged/bus/api/SubscribeEvent.java" to "package net.neoforged.bus.api; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface SubscribeEvent { EventPriority priority() default EventPriority.NORMAL; boolean receiveCanceled() default false; }",
            "net/neoforged/neoforge/client/event/ClientTickEvent.java" to """
                package net.neoforged.neoforge.client.event;
                import net.neoforged.neoforge.event.TickEvent.Phase;
                public class ClientTickEvent {
                    public final Phase phase;
                    public ClientTickEvent(Phase phase) { this.phase = phase; }
                    public static final class Pre extends ClientTickEvent { public Pre(Phase phase) { super(phase); } }
                    public static final class Post extends ClientTickEvent { public Post(Phase phase) { super(phase); } }
                }
            """.trimIndent()
        )
        val files = sources.map { (name, content) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
            path.toString()
        }
        val diagnostics = java.io.ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "-proc:none", "-d", root.toString(), *files.toTypedArray()), diagnostics.toString())
        return URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val hooks = loader.loadClass("Hooks")
            val phaseType = loader.loadClass("net.neoforged.neoforge.event.TickEvent\$Phase")
            val activeField = hooks.getDeclaredField("active").also { it.isAccessible = true }
            val trace = hooks.getDeclaredField("trace").also { it.isAccessible = true }.get(null) as StringBuilder
            listOf(false, true).map { active ->
                activeField.setBoolean(null, active)
                trace.setLength(0)
                for (phase in phaseType.enumConstants) {
                    val suffix = if (split && phase.toString() == "START") "Pre" else "Post"
                    val eventType = loader.loadClass("net.neoforged.neoforge.client.event.ClientTickEvent\$$suffix")
                    val event = eventType.getConstructor(phaseType).newInstance(phase)
                    hooks.getDeclaredMethod("onTick", eventType).also { it.isAccessible = true }.invoke(null, event)
                }
                trace.toString()
            }
        }
    }
}
