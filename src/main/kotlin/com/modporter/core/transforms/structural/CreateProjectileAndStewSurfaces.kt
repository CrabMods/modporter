package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Source-bound Create migrations for the 1.21 ProjectileItem dispenser contract and
 * suspicious-stew data component. Every mutation is planned and validated before any
 * source, mixin configuration, or accessor file is changed.
 */
internal object CreateProjectileAndStewSurfaces {
    const val RULE_ID = "struct-create-projectile-stew-surfaces"

    private const val PROJECTILE_BEHAVIOR =
        "net.minecraft.core.dispenser.ProjectileDispenseBehavior"
    private const val PROJECTILE_ITEM =
        "net.minecraft.world.item.ProjectileItem"
    private const val DISPENSE_CONFIG =
        "net.minecraft.world.item.ProjectileItem\$DispenseConfig"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val DATA_COMPONENTS =
        "net.minecraft.core.component.DataComponents"
    private const val STEW_EFFECTS =
        "net.minecraft.world.item.component.SuspiciousStewEffects"
    private const val STEW_ENTRY =
        "net.minecraft.world.item.component.SuspiciousStewEffects\$Entry"

    private const val STEW_ACCESSOR_FQN =
        "com.simibubi.create.foundation.mixin.accessor.SuspiciousStewItemAccessor"
    private const val STEW_ACCESSOR_SIMPLE = "SuspiciousStewItemAccessor"
    private const val STEW_MIXIN_ENTRY = "accessor.SuspiciousStewItemAccessor"
    private const val STEW_INVOKER = "create\$listPotionEffects"

    private data class WritePlan(
        val file: Path,
        val original: String,
        val migrated: String,
        val description: String,
        val before: String,
        val after: String,
        val ruleSuffix: String
    )

    private data class DeletePlan(
        val file: Path,
        val description: String,
        val before: String,
        val after: String,
        val ruleSuffix: String
    )

    private data class Plans(
        val writes: List<WritePlan> = emptyList(),
        val deletes: List<DeletePlan> = emptyList()
    ) {
        operator fun plus(other: Plans): Plans =
            Plans(writes + other.writes, deletes + other.deletes)
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): List<Change> {
        val javaRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(javaRoot)) return emptyList()

        val plans = planProjectile(pass, javaRoot) +
            planSuspiciousStew(pass, projectDir, javaRoot)
        if (plans.writes.isEmpty() && plans.deletes.isEmpty()) {
            return emptyList()
        }

        check(targetContractsVerified()) {
            "Create projectile/stew migration target ABI is not the verified " +
                "Minecraft 1.21.1 NeoForge contract"
        }
        validateJavaWrites(plans.writes)

        if (!dryRun) {
            plans.writes.forEach { it.file.writeText(it.migrated) }
            plans.deletes.forEach { Files.delete(it.file) }
        }

        return buildList {
            plans.writes.forEach { plan ->
                add(
                    Change(
                        file = plan.file,
                        line = 1,
                        description = plan.description,
                        before = plan.before,
                        after = plan.after,
                        confidence = Confidence.HIGH,
                        ruleId = "$RULE_ID-${plan.ruleSuffix}"
                    )
                )
            }
            plans.deletes.forEach { plan ->
                add(
                    Change(
                        file = plan.file,
                        line = 1,
                        description = plan.description,
                        before = plan.before,
                        after = plan.after,
                        confidence = Confidence.HIGH,
                        ruleId = "$RULE_ID-${plan.ruleSuffix}"
                    )
                )
            }
        }
    }

    private fun planProjectile(
        pass: StructuralRefactorPass,
        javaRoot: Path
    ): Plans {
        val accessor = javaRoot.resolve(
            "com/simibubi/create/foundation/mixin/accessor/" +
                "AbstractProjectileDispenseBehaviorAccessor.java"
        )
        val mounted = javaRoot.resolve(
            "com/simibubi/create/api/contraption/dispenser/" +
                "MountedProjectileDispenseBehavior.java"
        )
        if (!accessor.isRegularFile() && !mounted.isRegularFile()) {
            return Plans()
        }
        check(accessor.isRegularFile() && mounted.isRegularFile()) {
            "Create projectile wrapper is incomplete: accessor=$accessor, mounted=$mounted"
        }

        val accessorSource = accessor.readText()
        val mountedSource = mounted.readText()
        val accessorMigrated = isMigratedProjectileAccessor(accessorSource)
        val mountedMigrated = isMigratedMountedProjectile(mountedSource)
        if (accessorMigrated || mountedMigrated) {
            check(accessorMigrated && mountedMigrated) {
                "Create projectile migration is only partially applied"
            }
            return Plans()
        }

        check(
            accessorSource.contains("""@Invoker("getProjectile")""") &&
                accessorSource.contains("""@Invoker("getUncertainty")""") &&
                accessorSource.contains("""@Invoker("getPower")""") &&
                accessorSource.contains("create\$callGetProjectile") &&
                accessorSource.contains("create\$callGetUncertainty") &&
                accessorSource.contains("create\$callGetPower")
        ) {
            "Create projectile accessor no longer has the exact three legacy invokers"
        }
        check(
            Regex(
                """@Mixin\s*\(\s*(?:AbstractProjectileDispenseBehavior|""" +
                    """ProjectileDispenseBehavior)\.class\s*\)"""
            ).containsMatchIn(accessorSource)
        ) {
            "Create projectile accessor does not target the legacy or 1.21 dispenser type"
        }

        val codeFiles = codeFiles(javaRoot.parent.parent)
        val deadNames = listOf(
            "create\$callGetProjectile",
            "create\$callGetUncertainty",
            "create\$callGetPower"
        )
        val externalUsers = codeFiles.filter { file ->
            file != accessor && deadNames.any { file.readText().contains(it) }
        }
        check(externalUsers == listOf(mounted)) {
            "Create projectile invokers have unexpected callers: $externalUsers"
        }
        deadNames.forEach { name ->
            check(tokenCount(mountedSource, name) == 1) {
                "Create mounted projectile wrapper must call $name exactly once"
            }
        }

        val migratedAccessor = migratedProjectileAccessor(accessorSource)
        val migratedMounted = migrateMountedProjectile(pass, mountedSource)
        check(deadNames.none(migratedAccessor::contains)) {
            "Create projectile accessor still contains a removed invoker"
        }
        check(deadNames.none(migratedMounted::contains)) {
            "Create mounted projectile wrapper still calls a removed invoker"
        }

        return Plans(
            writes = listOf(
                WritePlan(
                    accessor,
                    accessorSource,
                    migratedAccessor,
                    "Retarget Create's projectile mixin to the captured 1.21 " +
                        "ProjectileItem and DispenseConfig fields",
                    "three removed AbstractProjectileDispenseBehavior invokers",
                    "two exact ProjectileDispenseBehavior field accessors",
                    "projectile-accessor"
                ),
                WritePlan(
                    mounted,
                    mountedSource,
                    migratedMounted,
                    "Preserve Create mounted projectile position and motion while " +
                        "using the captured 1.21 ProjectileItem contract",
                    "removed getProjectile/getPower/getUncertainty invokers",
                    "captured asProjectile and virtual shoot dispatch",
                    "mounted-projectile"
                )
            )
        )
    }

    private fun migratedProjectileAccessor(source: String): String {
        val packageName = Regex(
            """(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"""
        ).find(source)?.groupValues?.get(1)
        check(
            packageName ==
                "com.simibubi.create.foundation.mixin.accessor"
        ) {
            "Create projectile accessor has an unexpected package: $packageName"
        }
        val newline = newlineOf(source)
        return listOf(
            "package $packageName;",
            "",
            "import org.spongepowered.asm.mixin.Mixin;",
            "import org.spongepowered.asm.mixin.gen.Accessor;",
            "",
            "import net.minecraft.core.dispenser.ProjectileDispenseBehavior;",
            "import net.minecraft.world.item.ProjectileItem;",
            "",
            "@Mixin(ProjectileDispenseBehavior.class)",
            "public interface AbstractProjectileDispenseBehaviorAccessor {",
            "\t@Accessor(\"projectileItem\")",
            "\tProjectileItem create\$getProjectileItem();",
            "",
            "\t@Accessor(\"dispenseConfig\")",
            "\tProjectileItem.DispenseConfig create\$getDispenseConfig();",
            "}",
            ""
        ).joinToString(newline)
    }

    private fun migrateMountedProjectile(
        pass: StructuralRefactorPass,
        source: String
    ): String {
        val signature = Regex(
            """public\s+static\s+MountedDispenseBehavior\s+of\s*\(\s*""" +
                """(?:AbstractProjectileDispenseBehavior|ProjectileDispenseBehavior)""" +
                """\s+vanillaBehaviour\s*\)\s*\{"""
        ).findAll(source).toList()
        check(signature.size == 1) {
            "Create mounted projectile wrapper must declare exactly one of(...) method"
        }
        val method = signature.single()
        val openBrace = source.indexOf('{', method.range.first)
        val closeBrace = with(pass) {
            findMatchingBrace(source, openBrace)
        }
        check(openBrace >= 0 && closeBrace > openBrace) {
            "Create mounted projectile of(...) method has no closed body"
        }
        val oldBody = source.substring(openBrace + 1, closeBrace)
        check(
            oldBody.contains("AbstractProjectileDispenseBehaviorAccessor") &&
                oldBody.contains("create\$callGetProjectile") &&
                oldBody.contains("create\$callGetUncertainty") &&
                oldBody.contains("create\$callGetPower")
        ) {
            "Create mounted projectile of(...) method is not the exact legacy wrapper"
        }

        val newline = newlineOf(source)
        val replacement = listOf(
            "public static MountedDispenseBehavior of(" +
                "ProjectileDispenseBehavior vanillaBehaviour) {",
            "\t\tAbstractProjectileDispenseBehaviorAccessor accessor =",
            "\t\t\t(AbstractProjectileDispenseBehaviorAccessor) vanillaBehaviour;",
            "\t\tProjectileItem projectileItem = accessor.create\$getProjectileItem();",
            "\t\tProjectileItem.DispenseConfig dispenseConfig =",
            "\t\t\taccessor.create\$getDispenseConfig();",
            "\t\treturn new DefaultMountedDispenseBehavior() {",
            "\t\t\t@Override",
            "\t\t\tprotected ItemStack execute(",
            "\t\t\t\tItemStack stack, MovementContext context, BlockPos pos, Vec3 facing",
            "\t\t\t) {",
            "\t\t\t\tdouble x = pos.getX() + facing.x * .7 + .5;",
            "\t\t\t\tdouble y = pos.getY() + facing.y * .7 + .5;",
            "\t\t\t\tdouble z = pos.getZ() + facing.z * .7 + .5;",
            "\t\t\t\tDirection direction =",
            "\t\t\t\t\tMountedDispenseBehavior.getClosestFacingDirection(facing);",
            "\t\t\t\tProjectile projectile = projectileItem.asProjectile(",
            "\t\t\t\t\tcontext.world, new Vec3(x, y, z), stack.copy(), direction);",
            "\t\t\t\tif (projectile == null)",
            "\t\t\t\t\treturn stack;",
            "",
            "\t\t\t\tVec3 motion = facing.scale(dispenseConfig.power())",
            "\t\t\t\t\t.add(context.motion);",
            "\t\t\t\tprojectileItem.shoot(",
            "\t\t\t\t\tprojectile, motion.x, motion.y, motion.z,",
            "\t\t\t\t\t(float) motion.length(), dispenseConfig.uncertainty());",
            "\t\t\t\tcontext.world.addFreshEntity(projectile);",
            "\t\t\t\tstack.shrink(1);",
            "\t\t\t\treturn stack;",
            "\t\t\t}",
            "",
            "\t\t\t@Override",
            "\t\t\tprotected void playSound(LevelAccessor level, BlockPos pos) {",
            "\t\t\t\tlevel.levelEvent(",
            "\t\t\t\t\tdispenseConfig.overrideDispenseEvent().orElse(",
            "\t\t\t\t\t\tLevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH),",
            "\t\t\t\t\tpos, 0);",
            "\t\t\t}",
            "\t\t};",
            "\t}"
        ).joinToString(newline)

        var result = source.substring(0, method.range.first) +
            replacement +
            source.substring(closeBrace + 1)
        result = result.replace(
            "{@link AbstractProjectileDispenseBehavior}",
            "{@link ProjectileDispenseBehavior}"
        )
        result = with(pass) {
            removeImport(
                result,
                "net.minecraft.core.dispenser.AbstractProjectileDispenseBehavior"
            )
        }
        result = with(pass) {
            removeImport(result, "net.minecraft.core.PositionImpl")
        }
        result = with(pass) {
            addImportIfMissing(
                result,
                "net.minecraft.core.dispenser.ProjectileDispenseBehavior"
            )
        }
        result = with(pass) {
            addImportIfMissing(result, "net.minecraft.core.Direction")
        }
        result = with(pass) {
            addImportIfMissing(result, PROJECTILE_ITEM)
        }
        return result
    }

    private fun planSuspiciousStew(
        pass: StructuralRefactorPass,
        projectDir: Path,
        javaRoot: Path
    ): Plans {
        val hitActions = javaRoot.resolve(
            "com/simibubi/create/content/equipment/potatoCannon/" +
                "AllPotatoProjectileEntityHitActions.java"
        )
        val accessor = javaRoot.resolve(
            "com/simibubi/create/foundation/mixin/accessor/" +
                "SuspiciousStewItemAccessor.java"
        )
        if (!hitActions.isRegularFile() && !accessor.isRegularFile()) {
            return Plans()
        }
        check(hitActions.isRegularFile()) {
            "Create suspicious-stew action source is missing: $hitActions"
        }

        val hitSource = hitActions.readText()
        val oldInvocation = hitSource.contains(
            "$STEW_ACCESSOR_SIMPLE.$STEW_INVOKER"
        )
        val migratedInvocation = isMigratedStewHitAction(hitSource)
        if (!oldInvocation && !migratedInvocation) {
            check(!accessor.isRegularFile()) {
                "Create suspicious-stew accessor remains but its only known caller " +
                    "has an unknown source shape"
            }
            return Plans()
        }

        val codeFiles = codeFiles(projectDir.resolve("src"))
        val sourceByFile = codeFiles.associateWith { it.readText() }.toMutableMap()
        val writes = mutableListOf<WritePlan>()
        if (oldInvocation) {
            val users = sourceByFile.filter { (file, source) ->
                file != accessor && source.contains(STEW_ACCESSOR_SIMPLE)
            }.keys.toList()
            check(users == listOf(hitActions)) {
                "Create suspicious-stew accessor has unexpected callers: $users"
            }
            check(tokenCount(hitSource, STEW_INVOKER) == 1) {
                "Create suspicious-stew action must invoke $STEW_INVOKER exactly once"
            }
            val migrated = migrateStewHitAction(pass, hitSource)
            sourceByFile[hitActions] = migrated
            writes += WritePlan(
                hitActions,
                hitSource,
                migrated,
                "Read suspicious-stew effects from the 1.21 data component in " +
                    "stored list order",
                "removed SuspiciousStewItem.listPotionEffects invoker",
                "SUSPICIOUS_STEW_EFFECTS entries and createEffectInstance",
                "stew-call"
            )
        }

        val remainingUsers = sourceByFile.filter { (file, source) ->
            file != accessor && source.contains(STEW_ACCESSOR_SIMPLE)
        }.keys
        check(remainingUsers.isEmpty()) {
            "Create suspicious-stew accessor still has callers after migration: " +
                remainingUsers.joinToString()
        }

        if (!accessor.isRegularFile()) {
            check(!oldInvocation) {
                "Create suspicious-stew invocation exists without its accessor source"
            }
            check(findMixinConfigs(projectDir).none {
                it.readText().contains("\"$STEW_MIXIN_ENTRY\"")
            }) {
                "Create suspicious-stew mixin config entry remains without its source"
            }
            return Plans(writes = writes)
        }

        val accessorSource = accessor.readText()
        check(
            accessorSource.contains("@Mixin(SuspiciousStewItem.class)") &&
                accessorSource.contains("""@Invoker("listPotionEffects")""") &&
                accessorSource.contains(STEW_INVOKER)
        ) {
            "Create suspicious-stew accessor is not the exact removed native invoker"
        }

        val configs = findMixinConfigs(projectDir)
        val owningConfigs = configs.filter {
            it.readText().contains("\"$STEW_MIXIN_ENTRY\"")
        }
        check(owningConfigs.size == 1) {
            "Create suspicious-stew accessor must have exactly one mixin config " +
                "entry, found ${owningConfigs.size}"
        }
        val config = owningConfigs.single()
        val configSource = config.readText()
        val entryLine = Regex(
            """(?m)^[ \t]*"${Regex.escape(STEW_MIXIN_ENTRY)}",[ \t]*(?:\r?\n|\z)"""
        )
        val lines = entryLine.findAll(configSource).toList()
        check(lines.size == 1) {
            "Create suspicious-stew mixin entry is not one independently removable line"
        }
        val match = lines.single()
        val migratedConfig = configSource.removeRange(
            match.range.first,
            match.range.last + 1
        )
        check(!migratedConfig.contains("\"$STEW_MIXIN_ENTRY\"")) {
            "Create suspicious-stew mixin entry survived its owned config edit"
        }
        writes += WritePlan(
            config,
            configSource,
            migratedConfig,
            "Remove only the obsolete suspicious-stew accessor registration after " +
                "all caller proof succeeds",
            "\"$STEW_MIXIN_ENTRY\",",
            "all other common mixin entries retained",
            "stew-config"
        )

        return Plans(
            writes = writes,
            deletes = listOf(
                DeletePlan(
                    accessor,
                    "Remove the obsolete suspicious-stew invoker source after its " +
                        "single caller and owned config entry are migrated",
                    """@Invoker("listPotionEffects")""",
                    "direct SUSPICIOUS_STEW_EFFECTS iteration",
                    "stew-accessor-delete"
                )
            )
        )
    }

    private fun migrateStewHitAction(
        pass: StructuralRefactorPass,
        source: String
    ): String {
        val pattern = Regex(
            "(?m)^([ \\t]*)if[ \\t]*\\([ \\t]*ray\\.getEntity\\(\\)" +
                "[ \\t]+instanceof[ \\t]+LivingEntity[ \\t]+livingEntity" +
                "[ \\t]*\\)[ \\t]*\\r?\\n([ \\t]+)" +
                Regex.escape("$STEW_ACCESSOR_SIMPLE.$STEW_INVOKER") +
                "\\(projectile,[ \\t]*livingEntity::addEffect\\);" +
                "[ \\t]*$"
        )
        val matches = pattern.findAll(source).toList()
        check(matches.size == 1) {
            "Create suspicious-stew action must retain one exact invoker statement"
        }
        val match = matches.single()
        val indent = match.groupValues[1]
        val childIndent = match.groupValues[2]
        check(childIndent.startsWith(indent) && childIndent.length > indent.length) {
            "Create suspicious-stew action has an invalid nested indentation boundary"
        }
        val deepIndent = childIndent + "\t"
        val newline = newlineOf(source)
        val replacement = listOf(
            "${indent}if (ray.getEntity() instanceof LivingEntity livingEntity) {",
            "${childIndent}for (SuspiciousStewEffects.Entry effect : " +
                "projectile.getOrDefault(",
            "${deepIndent}DataComponents.SUSPICIOUS_STEW_EFFECTS,",
            "${deepIndent}SuspiciousStewEffects.EMPTY).effects()) {",
            "${deepIndent}livingEntity.addEffect(effect.createEffectInstance());",
            "${childIndent}}",
            "${indent}}"
        ).joinToString(newline)

        var result = source.substring(0, match.range.first) +
            replacement +
            source.substring(match.range.last + 1)
        result = with(pass) {
            removeImport(result, STEW_ACCESSOR_FQN)
        }
        result = with(pass) {
            addImportIfMissing(result, DATA_COMPONENTS)
        }
        result = with(pass) {
            addImportIfMissing(result, STEW_EFFECTS)
        }
        check(!result.contains(STEW_ACCESSOR_SIMPLE)) {
            "Create suspicious-stew action still references its obsolete accessor"
        }
        return result
    }

    private fun isMigratedProjectileAccessor(source: String): Boolean =
        source.contains("""@Accessor("projectileItem")""") &&
            source.contains("""@Accessor("dispenseConfig")""") &&
            source.contains("create\$getProjectileItem") &&
            source.contains("create\$getDispenseConfig") &&
            !source.contains("@Invoker(")

    private fun isMigratedMountedProjectile(source: String): Boolean =
        source.contains("accessor.create\$getProjectileItem()") &&
            source.contains("accessor.create\$getDispenseConfig()") &&
            source.contains("projectileItem.asProjectile(") &&
            source.contains("new Vec3(x, y, z)") &&
            source.contains("stack.copy(), direction") &&
            source.contains("projectileItem.shoot(") &&
            source.contains("facing.scale(dispenseConfig.power())") &&
            source.contains("(float) motion.length()") &&
            source.contains("dispenseConfig.uncertainty()") &&
            source.contains("dispenseConfig.overrideDispenseEvent().orElse(") &&
            source.contains("context.world.addFreshEntity(projectile)") &&
            source.contains("stack.shrink(1)") &&
            !source.contains("positionFunction()") &&
            !source.contains("create\$callGetProjectile")

    private fun isMigratedStewHitAction(source: String): Boolean =
        !source.contains(STEW_ACCESSOR_SIMPLE) &&
            source.contains(
                "import net.minecraft.core.component.DataComponents;"
            ) &&
            source.contains(
                "import net.minecraft.world.item.component." +
                    "SuspiciousStewEffects;"
            ) &&
            Regex(
                """for\s*\(\s*SuspiciousStewEffects\.Entry\s+\w+\s*:\s*""" +
                    """projectile\.getOrDefault\s*\(\s*""" +
                    """DataComponents\.SUSPICIOUS_STEW_EFFECTS\s*,\s*""" +
                    """SuspiciousStewEffects\.EMPTY\s*\)\.effects\s*\(\s*\)""" +
                    """\s*\)"""
            ).containsMatchIn(source) &&
            Regex(
                """livingEntity\.addEffect\s*\(\s*\w+""" +
                    """\.createEffectInstance\s*\(\s*\)\s*\)"""
            ).containsMatchIn(source)

    private fun validateJavaWrites(writes: List<WritePlan>) {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        writes.filter { it.file.fileName.toString().endsWith(".java") }
            .forEach { plan ->
                val parsed = parser.parse(plan.migrated)
                check(parsed.isSuccessful && parsed.result.isPresent) {
                    "Create projectile/stew migration emitted invalid Java in " +
                        "${plan.file}: ${parsed.problems.joinToString()}"
                }
            }
    }

    private fun targetContractsVerified(): Boolean {
        val asProjectile = TargetMembers.inheritedMethod(
            PROJECTILE_ITEM,
            "asProjectile",
            "(Lnet/minecraft/world/level/Level;" +
                "Lnet/minecraft/core/Position;" +
                "Lnet/minecraft/world/item/ItemStack;" +
                "Lnet/minecraft/core/Direction;)" +
                "Lnet/minecraft/world/entity/projectile/Projectile;"
        )
        val shoot = TargetMembers.inheritedMethod(
            PROJECTILE_ITEM,
            "shoot",
            "(Lnet/minecraft/world/entity/projectile/Projectile;DDDFF)V"
        )
        val power = TargetMembers.inheritedMethod(
            DISPENSE_CONFIG,
            "power",
            "()F"
        )
        val uncertainty = TargetMembers.inheritedMethod(
            DISPENSE_CONFIG,
            "uncertainty",
            "()F"
        )
        val overrideEvent = TargetMembers.inheritedMethod(
            DISPENSE_CONFIG,
            "overrideDispenseEvent",
            "()Ljava/util/OptionalInt;"
        )
        val getOrDefault = TargetMembers.inheritedMethod(
            ITEM_STACK,
            "getOrDefault",
            "(Lnet/minecraft/core/component/DataComponentType;" +
                "Ljava/lang/Object;)Ljava/lang/Object;"
        )
        val effects = TargetMembers.inheritedMethod(
            STEW_EFFECTS,
            "effects",
            "()Ljava/util/List;"
        )
        val createEffect = TargetMembers.inheritedMethod(
            STEW_ENTRY,
            "createEffectInstance",
            "()Lnet/minecraft/world/effect/MobEffectInstance;"
        )
        return TargetMembers.fieldDescriptor(
            PROJECTILE_BEHAVIOR,
            "projectileItem"
        ) == "Lnet/minecraft/world/item/ProjectileItem;" &&
            TargetMembers.fieldDescriptor(
                PROJECTILE_BEHAVIOR,
                "dispenseConfig"
            ) == "Lnet/minecraft/world/item/ProjectileItem\$DispenseConfig;" &&
            asProjectile?.owner == PROJECTILE_ITEM &&
            asProjectile.access == "public" &&
            shoot?.owner == PROJECTILE_ITEM &&
            shoot.access == "public" &&
            power?.owner == DISPENSE_CONFIG &&
            power.access == "public" &&
            uncertainty?.owner == DISPENSE_CONFIG &&
            uncertainty.access == "public" &&
            overrideEvent?.owner == DISPENSE_CONFIG &&
            overrideEvent.access == "public" &&
            getOrDefault?.access == "public" &&
            effects?.owner == STEW_EFFECTS &&
            effects.access == "public" &&
            createEffect?.owner == STEW_ENTRY &&
            createEffect.access == "public" &&
            TargetMembers.fieldDescriptor(
                DATA_COMPONENTS,
                "SUSPICIOUS_STEW_EFFECTS"
            ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                STEW_EFFECTS,
                "EMPTY"
            ) == "Lnet/minecraft/world/item/component/SuspiciousStewEffects;" &&
            TargetMembers.inheritsFrom(
                "net.minecraft.world.phys.Vec3",
                "net.minecraft.core.Position"
            )
    }

    private fun codeFiles(root: Path): List<Path> =
        Files.walk(root).use { files ->
            files.filter { it.isRegularFile() }
                .filter {
                    val name = it.fileName.toString()
                    name.endsWith(".java") ||
                        name.endsWith(".kt") ||
                        name.endsWith(".kts")
                }
                .sorted()
                .toList()
        }

    private fun findMixinConfigs(projectDir: Path): List<Path> {
        val resources = projectDir.resolve("src/main/resources")
        if (!Files.isDirectory(resources)) return emptyList()
        return Files.walk(resources).use { files ->
            files.filter { it.isRegularFile() }
                .filter { it.fileName.toString().endsWith(".mixins.json") }
                .sorted()
                .toList()
        }
    }

    private fun tokenCount(source: String, token: String): Int =
        Regex("""(?<![\w$])${Regex.escape(token)}(?![\w$])""")
            .findAll(source)
            .count()

    private fun newlineOf(source: String): String =
        if (source.contains("\r\n")) "\r\n" else "\n"
}
