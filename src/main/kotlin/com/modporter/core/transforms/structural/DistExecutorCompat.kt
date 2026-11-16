package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * NeoForge has no `DistExecutor`. Statement-shaped uses become `FMLEnvironment.dist` guards earlier
 * in the pass; whatever is left (an expression inside a lambda, a `Supplier<Runnable>` held in a
 * variable, a `this::method` reference) keeps the Forge API through a generated class of the same
 * name in the mod's compat package, implemented on `FMLEnvironment.dist` exactly as Forge's was.
 */
internal object DistExecutorCompat {
    const val RULE_ID = "struct-dist-executor-compat"
    private val imports = listOf("net.neoforged.fml.DistExecutor", "net.minecraftforge.fml.DistExecutor")

    fun migrate(projectDir: Path, compatPackageSupplier: () -> String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        // a use with no import at all is a leftover too: the Forge import was dropped by an earlier rule
        val leftovers = files.filter { file ->
            val source = runCatching { file.readText() }.getOrDefault("")
            Regex("""(?<![\w$.])DistExecutor\.""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source)) &&
                !Regex("""(?m)^import\s+com\.modporter\.generated\.[\w.]+\.DistExecutor;""").containsMatchIn(source)
        }
        if (leftovers.isEmpty()) return emptyList()
        val compatPackage = compatPackageSupplier()
        val changes = mutableListOf<Change>()
        val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve("DistExecutor.java")
        val compatSource = compatSource(compatPackage)
        if (!compatFile.exists() || compatFile.readText() != compatSource) {
            if (!dryRun) {
                compatFile.parent.createDirectories()
                compatFile.writeText(compatSource)
            }
            changes += Change(compatFile, 0, "Generate the DistExecutor compat class on FMLEnvironment.dist", "(none)", "$compatPackage.DistExecutor", Confidence.HIGH, RULE_ID)
        }
        for (file in leftovers) {
            val source = file.readText()
            var migrated = source
            for (import in imports) migrated = JavaSourceText.removeImport(migrated, import)
            if (JavaSourceText.packageName(migrated) != compatPackage) migrated = JavaSourceText.addImport(migrated, "$compatPackage.DistExecutor")
            if (migrated == source) continue
            if (!dryRun) file.writeText(migrated)
            changes += Change(file, 1, "DistExecutor calls that are expressions keep the Forge API through the generated compat class", "net.neoforged.fml.DistExecutor", "$compatPackage.DistExecutor", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    private fun compatSource(compatPackage: String): String = """
        |package $compatPackage;
        |
        |import java.util.concurrent.Callable;
        |import java.util.function.Supplier;
        |import net.neoforged.api.distmarker.Dist;
        |import net.neoforged.fml.loading.FMLEnvironment;
        |
        |/** Forge's DistExecutor contract on NeoForge's FMLEnvironment.dist. */
        |public final class DistExecutor {
        |    private DistExecutor() {
        |    }
        |
        |    public static void runWhenOn(Dist dist, Supplier<Runnable> toRun) {
        |        if (FMLEnvironment.dist == dist) {
        |            toRun.get().run();
        |        }
        |    }
        |
        |    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        |        runWhenOn(dist, toRun);
        |    }
        |
        |    public static void safeRunWhenOn(Dist dist, Supplier<SafeRunnable> toRun) {
        |        if (FMLEnvironment.dist == dist) {
        |            toRun.get().run();
        |        }
        |    }
        |
        |    public static <T> T callWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        |        if (FMLEnvironment.dist != dist) {
        |            return null;
        |        }
        |        try {
        |            return toRun.get().call();
        |        } catch (Exception e) {
        |            throw new RuntimeException(e);
        |        }
        |    }
        |
        |    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        |        return callWhenOn(dist, toRun);
        |    }
        |
        |    public static <T> T safeCallWhenOn(Dist dist, Supplier<SafeCallable<T>> toRun) {
        |        if (FMLEnvironment.dist != dist) {
        |            return null;
        |        }
        |        try {
        |            return toRun.get().call();
        |        } catch (Exception e) {
        |            throw new RuntimeException(e);
        |        }
        |    }
        |
        |    public static <T> T runForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        |        return FMLEnvironment.dist == Dist.CLIENT ? clientTarget.get().get() : serverTarget.get().get();
        |    }
        |
        |    public static <T> T unsafeRunForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        |        return runForDist(clientTarget, serverTarget);
        |    }
        |
        |    public static <T> T safeRunForDist(Supplier<SafeSupplier<T>> clientTarget, Supplier<SafeSupplier<T>> serverTarget) {
        |        return FMLEnvironment.dist == Dist.CLIENT ? clientTarget.get().get() : serverTarget.get().get();
        |    }
        |
        |    public interface SafeReferent {
        |    }
        |
        |    public interface SafeRunnable extends SafeReferent, Runnable {
        |    }
        |
        |    public interface SafeCallable<T> extends SafeReferent, Callable<T>, Supplier<T> {
        |        @Override
        |        default T get() {
        |            try {
        |                return call();
        |            } catch (Exception e) {
        |                throw new RuntimeException(e);
        |            }
        |        }
        |    }
        |
        |    public interface SafeSupplier<T> extends SafeReferent, Supplier<T> {
        |    }
        |}
        |""".trimMargin()
}
