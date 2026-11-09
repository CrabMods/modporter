package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge's RecipeWrapper implemented Container. NeoForge 1.21 makes it a read-only RecipeInput, so
 * declarations that explicitly require Container receive a generated adapter over the same
 * IItemHandlerModifiable.
 */
internal object LegacyRecipeWrapperContainers {
    const val RULE_ID = "struct-legacy-recipe-wrapper-containers"

    fun migrate(projectDir: Path, compatPackageSupplier: () -> String, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
                .toList()
        }
        val candidates = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
            .filterValues { source ->
                source.contains("Container") &&
                    source.contains("new RecipeWrapper(") &&
                    source.contains("ItemStackHandler")
            }
        if (candidates.isEmpty()) return emptyList()

        val migratable = candidates.filterValues { source ->
            migrateSource(source, "com.modporter.generated.__recipe_wrapper_probe__") != source
        }
        if (migratable.isEmpty()) return emptyList()

        val compatPackage = compatPackageSupplier()
        val changes = mutableListOf<Change>()
        var migratedAny = false
        for ((file, source) in migratable) {
            val result = migrateSource(source, compatPackage)
            if (result == source) continue
            migratedAny = true
            if (!dryRun) file.writeText(result)
            changes += Change(
                file,
                1,
                "Container declarations keep Forge RecipeWrapper behavior through an item-handler adapter",
                "Container = new RecipeWrapper(handler)",
                "Container = new LegacyItemHandlerContainer(handler)",
                Confidence.HIGH,
                RULE_ID
            )
        }
        if (!migratedAny) return changes

        val generated = sourceRoot.resolve(compatPackage.replace('.', '/'))
            .resolve("LegacyItemHandlerContainer.java")
        val content = helperSource(compatPackage)
        if (!generated.toFile().exists() || runCatching { generated.readText() }.getOrNull() != content) {
            if (!dryRun) {
                generated.parent.createDirectories()
                generated.writeText(content)
            }
            changes += Change(
                generated,
                1,
                "Generate the Forge RecipeWrapper Container contract over IItemHandlerModifiable",
                "",
                "LegacyItemHandlerContainer",
                Confidence.HIGH,
                RULE_ID
            )
        }
        return changes
    }

    internal fun migrateSource(source: String, compatPackage: String): String {
        if (!source.contains("new RecipeWrapper(") || !source.contains("Container")) return source
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val unit = parser.parse(source).result.orElse(null) ?: return source
        val imports = unit.imports.filter { !it.isStatic && !it.isAsterisk }
            .associate { it.name.identifier to it.nameAsString }
        if (unit.findAll(TypeDeclaration::class.java).any {
                it.nameAsString in setOf("Container", "RecipeWrapper", "ItemStackHandler", "IItemHandlerModifiable")
            }
        ) {
            return source
        }
        fun canonical(type: String): String {
            val raw = type.substringBefore('<').trim()
            return if ('.' in raw) raw else imports[raw] ?: raw
        }
        if (imports["Container"] != "net.minecraft.world.Container" ||
            imports["RecipeWrapper"] != "net.neoforged.neoforge.items.wrapper.RecipeWrapper"
        ) {
            return source
        }

        val variables = unit.findAll(VariableDeclarator::class.java)
        val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }
        fun start(node: Node): Int =
            node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
        fun end(node: Node): Int =
            node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

        fun handlerArgumentIsCanonical(argument: com.github.javaparser.ast.expr.Expression): Boolean {
            val created = argument as? ObjectCreationExpr
            if (created != null) {
                return canonical(created.type.toString()) ==
                    "net.neoforged.neoforge.items.ItemStackHandler"
            }
            val name = argument.toString().trim()
            if (!Regex("""[A-Za-z_$][\w$]*""").matches(name)) return false
            val declarations = variables.filter {
                it.nameAsString == name &&
                    start(it) < start(argument) &&
                    canonical(it.type.toString()) in setOf(
                        "net.neoforged.neoforge.items.ItemStackHandler",
                        "net.neoforged.neoforge.items.IItemHandlerModifiable"
                    )
            }
            return declarations.size == 1
        }

        val edits = mutableListOf<Pair<IntRange, String>>()
        for (variable in variables) {
            if (canonical(variable.type.toString()) != "net.minecraft.world.Container") continue
            val initializer = variable.initializer.orElse(null) as? ObjectCreationExpr ?: continue
            if (canonical(initializer.type.toString()) !=
                "net.neoforged.neoforge.items.wrapper.RecipeWrapper" ||
                initializer.arguments.size != 1 ||
                !handlerArgumentIsCanonical(initializer.arguments.single())
            ) {
                continue
            }
            val from = start(initializer.type)
            val to = end(initializer.type)
            if (from >= 0 && to >= from) {
                edits += (from..to) to "$compatPackage.LegacyItemHandlerContainer"
            }
        }
        return edits.sortedByDescending { it.first.first }
            .fold(source) { text, (range, replacement) -> text.replaceRange(range, replacement) }
    }

    private fun helperSource(packageName: String): String = """
        package $packageName;

        import net.minecraft.world.Container;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.neoforged.neoforge.items.IItemHandlerModifiable;

        /** Forge 1.20 RecipeWrapper's Container contract over an item handler. */
        public final class LegacyItemHandlerContainer implements Container {
            private final IItemHandlerModifiable inventory;

            public LegacyItemHandlerContainer(IItemHandlerModifiable inventory) {
                this.inventory = inventory;
            }

            @Override
            public int getContainerSize() {
                return inventory.getSlots();
            }

            @Override
            public ItemStack getItem(int slot) {
                return inventory.getStackInSlot(slot);
            }

            @Override
            public ItemStack removeItem(int slot, int count) {
                ItemStack stack = inventory.getStackInSlot(slot);
                return stack.isEmpty() ? ItemStack.EMPTY : stack.split(count);
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                inventory.setStackInSlot(slot, stack);
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                ItemStack stack = getItem(slot);
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                setItem(slot, ItemStack.EMPTY);
                return stack;
            }

            @Override
            public boolean isEmpty() {
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    if (!inventory.getStackInSlot(slot).isEmpty()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return inventory.isItemValid(slot, stack);
            }

            @Override
            public void clearContent() {
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    inventory.setStackInSlot(slot, ItemStack.EMPTY);
                }
            }

            @Override
            public int getMaxStackSize() {
                return 0;
            }

            @Override
            public void setChanged() {
            }

            @Override
            public boolean stillValid(Player player) {
                return false;
            }

            @Override
            public void startOpen(Player player) {
            }

            @Override
            public void stopOpen(Player player) {
            }
        }
    """.trimIndent() + "\n"
}
