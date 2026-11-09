package com.modporter.core.transforms.structural

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
 * Forge 1.20.1's `LivingEntity.getEquipmentSlotForItem(ItemStack)` was static: the stack's own
 * `getEquipmentSlot()`, else its `Equipable` slot, else the main hand (Forge's LivingEntity patch).
 * 1.21.1 made it an instance method that also asks the entity whether it can use that slot, so a call
 * through the class name, with no entity to ask, goes to a generated helper keeping the static body.
 */
internal object LegacyEquipmentSlots {
    const val RULE_ID = "struct-legacy-equipment-slots"
    private val staticCall = Regex("""(?<![\w$.])(?:net\.minecraft\.world\.entity\.)?LivingEntity\s*\.\s*getEquipmentSlotForItem\s*\(""")

    fun migrate(projectDir: Path, compatPackageSupplier: () -> String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val callers = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
            .filterValues { staticCall.containsMatchIn(JavaSourceText.maskCommentsAndLiterals(it)) }
        if (callers.isEmpty()) return emptyList()
        val compatPackage = compatPackageSupplier()
        val changes = mutableListOf<Change>()
        for ((file, source) in callers) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            var result = source
            for (call in staticCall.findAll(masked).toList().asReversed()) {
                result = result.substring(0, call.range.first) + "$compatPackage.LegacyEquipmentSlots.forItem(" + result.substring(call.range.last + 1)
            }
            if (!dryRun) file.writeText(result)
            changes += Change(file, 1, "Static equipment slot lookups keep Forge 1.20.1's body",
                "LivingEntity.getEquipmentSlotForItem(stack)", "LegacyEquipmentSlots.forItem(stack)", Confidence.HIGH, RULE_ID)
        }
        val dir = srcDir.resolve(compatPackage.replace('.', '/'))
        if (!dryRun) {
            dir.createDirectories()
            dir.resolve("LegacyEquipmentSlots.java").writeText("""
                package $compatPackage;

                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.item.Equipable;
                import net.minecraft.world.item.ItemStack;

                /** Forge 1.20.1's static LivingEntity#getEquipmentSlotForItem. */
                public final class LegacyEquipmentSlots {
                    private LegacyEquipmentSlots() {
                    }

                    public static EquipmentSlot forItem(ItemStack stack) {
                        EquipmentSlot slot = stack.getEquipmentSlot();
                        if (slot != null) {
                            return slot;
                        }
                        Equipable equipable = Equipable.get(stack);
                        return equipable != null ? equipable.getEquipmentSlot() : EquipmentSlot.MAINHAND;
                    }
                }
            """.trimIndent() + "\n")
        }
        changes += Change(dir.resolve("LegacyEquipmentSlots.java"), 1, "Generated Forge 1.20.1 static equipment slot lookup",
            "", "LegacyEquipmentSlots", Confidence.HIGH, RULE_ID)
        return changes
    }
}
