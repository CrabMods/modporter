package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The removed PositionImpl was an identity object, not Vec3's coordinate-equality value type. */
internal fun StructuralRefactorPass.migrateLegacyPositionValues(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val changes = mutableListOf<Change>()
    var compat: String? = null
    for (file in files) {
        val source = file.readText()
        if (!source.contains("PositionImpl")) continue
        val unit = parser.parse(source).result.orElse(null) ?: continue
        if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "PositionImpl" }) continue
        val visible = unit.imports.any { !it.isStatic && (it.nameAsString == "net.minecraft.core.PositionImpl" || it.isAsterisk && it.nameAsString == "net.minecraft.core") }
        val uses = unit.findAll(ClassOrInterfaceType::class.java).filter { it.nameWithScope == "net.minecraft.core.PositionImpl" || it.nameWithScope == "PositionImpl" && visible }
        if (uses.isEmpty()) continue
        val pkg = compat ?: detectRequiredGeneratedCompatPackage(project, "legacy PositionImpl identity contract").also { compat = it }
        LexicalPreservingPrinter.setup(unit)
        for (type in uses) type.replace(parser.parseClassOrInterfaceType("$pkg.LegacyPositionImpl").result.orElseThrow())
        unit.imports.removeIf { it.nameAsString == "net.minecraft.core.PositionImpl" }
        val result = LexicalPreservingPrinter.print(unit)
        check(parser.parse(result).isSuccessful) { "Position value migration emitted invalid Java: $file" }
        if (!dryRun) file.writeText(result)
        changes += Change(file, 1, "Retain the old PositionImpl coordinates, subclass fields and identity equality", "PositionImpl", "LegacyPositionImpl implements Position", Confidence.HIGH, "struct-legacy-position-value")
    }
    val pkg = compat ?: return changes
    val generated = root.resolve(pkg.replace('.', '/')).resolve("LegacyPositionImpl.java")
    val content = """
        package $pkg;
        public class LegacyPositionImpl implements net.minecraft.core.Position {
            protected final double x;
            protected final double y;
            protected final double z;
            public LegacyPositionImpl(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
            @Override public double x() { return x; }
            @Override public double y() { return y; }
            @Override public double z() { return z; }
        }
    """.trimIndent()
    if (!java.nio.file.Files.exists(generated) || generated.readText() != content) {
        if (!dryRun) { generated.parent.createDirectories(); generated.writeText(content) }
        changes += Change(generated, 1, "Generate the verified 1.20.1 PositionImpl API on Position", "", "LegacyPositionImpl", Confidence.HIGH, "struct-legacy-position-value")
    }
    return changes
}
