package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Replaces the removed immutable PositionImpl value with the equivalent Vec3 position implementation. */
class LegacyPositionImplMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedSources = linkedMapOf<Path, String>()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }.forEach { file ->
            val source = file.readText()
            if (!source.contains("PositionImpl")) return@forEach
            val parsed = parser.parse(source)
            val cu = parsed.result.orElseThrow {
                IllegalStateException("Cannot parse legacy PositionImpl source $file: ${parsed.problems.joinToString()}")
            }
            val exactImport = cu.imports.any {
                !it.isStatic && it.nameAsString == "net.minecraft.core.PositionImpl"
            }
            if (!exactImport) {
                if (cu.findAll(TypeDeclaration::class.java).any { it.nameAsString == "PositionImpl" }) {
                    return@forEach
                }
                if (cu.findAll(ClassOrInterfaceType::class.java).any {
                        it.nameWithScope == "net.minecraft.core.PositionImpl"
                    }
                ) {
                    return@forEach
                }
                throw IllegalStateException("PositionImpl usage has no exact Minecraft owner import in $file")
            }
            if (cu.findAll(ClassOrInterfaceDeclaration::class.java).any { declaration ->
                    declaration.extendedTypes.any { it.nameWithScope == "PositionImpl" }
                }
            ) {
                return@forEach
            }
            val constructors = cu.findAll(ObjectCreationExpr::class.java).filter {
                it.typeAsString == "PositionImpl"
            }
            if (constructors.isEmpty()) {
                throw IllegalStateException("PositionImpl import has no executable constructor in $file")
            }
            if (constructors.any { it.arguments.size != 3 || it.anonymousClassBody.isPresent }) {
                throw IllegalStateException("PositionImpl construction is not an exact three-coordinate value in $file")
            }

            LexicalPreservingPrinter.setup(cu)
            cu.findAll(VariableDeclarator::class.java).filter {
                it.typeAsString == "PositionImpl"
            }.toList().forEach { it.setType(StaticJavaParser.parseType("Vec3")) }
            cu.findAll(ClassOrInterfaceType::class.java).filter {
                it.nameAsString == "PositionImpl"
            }.toList().forEach { it.setName("Vec3") }
            cu.imports.filter {
                !it.isStatic && it.nameAsString == "net.minecraft.core.PositionImpl"
            }.toList().forEach { it.remove() }
            cu.addImport("net.minecraft.world.phys.Vec3")
            val migrated = LexicalPreservingPrinter.print(cu)
            if (migrated.contains("PositionImpl")) {
                val remnants = migrated.lines().filter { it.contains("PositionImpl") }.joinToString(" | ")
                throw IllegalStateException("PositionImpl remains after exact value migration in $file: $remnants")
            }
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Replace removed PositionImpl values with equivalent Vec3 positions",
                before = "new PositionImpl(x, y, z)",
                after = "new Vec3(x, y, z)",
                confidence = Confidence.HIGH,
                ruleId = "struct-position-impl-vec3"
            )
        }

        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }
}
