package com.modporter.core.transforms.structural

import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.toList

/** Resolves BlockEntity factory method references to their exact project registry fields. */
internal class ExactBlockEntityRegistryGraph private constructor(
    private val referencesByBlockEntity: Map<String, List<String>>,
    private val index: JavaProjectTypeIndex?
) {
    fun referencesFor(blockEntityType: String): List<String> = referencesByBlockEntity[blockEntityType].orEmpty()

    fun referencesForAssignableTo(blockEntityType: String): List<String> {
        val typeIndex = index ?: return emptyList()
        return referencesByBlockEntity.entries.filter { (implementationType, _) ->
            typeIndex.isTypeAssignableTo(implementationType, blockEntityType)
        }.flatMap { it.value }.distinct()
    }

    companion object {
        private val supportedRegistryFieldTypes = setOf(
            "com.tterrag.registrate.util.entry.BlockEntityEntry",
            "net.neoforged.neoforge.registries.DeferredHolder",
            "net.minecraftforge.registries.RegistryObject",
            "java.util.function.Supplier"
        )
        private val supportedRegistryFieldSimpleNames = supportedRegistryFieldTypes.mapTo(mutableSetOf()) {
            it.substringAfterLast('.')
        }
        private const val BLOCK_ENTITY_TYPE = "net.minecraft.world.level.block.entity.BlockEntityType"

        fun build(sourceRoot: Path): ExactBlockEntityRegistryGraph {
            if (!Files.isDirectory(sourceRoot)) return ExactBlockEntityRegistryGraph(emptyMap(), null)
            val files = Files.walk(sourceRoot).use { stream ->
                stream.filter { it.extension == "java" }
                    .sorted(compareBy<Path> {
                        sourceRoot.relativize(it).toString().replace('\\', '/')
                    })
                    .toList()
            }
            val index = JavaProjectTypeIndex.build(sourceRoot)
            files.forEach { file -> index.unit(file) }
            val references = linkedMapOf<String, MutableSet<String>>()
            files.forEach { file ->
                index.unit(file).findAll(FieldDeclaration::class.java).forEach fields@{ field ->
                    if (!field.isStatic) return@fields
                    field.variables.forEach variables@{ variable ->
                        val rawType = variable.type as? ClassOrInterfaceType ?: return@variables
                        if (rawType.nameAsString !in supportedRegistryFieldSimpleNames) return@variables
                        val registryFieldType = index.declaredType(variable.type, variable) ?: return@variables
                        if (registryFieldType !in supportedRegistryFieldTypes) return@variables
                        val declaredTypes = variable.type.findAll(ClassOrInterfaceType::class.java).mapNotNull { type ->
                            index.declaredType(type, variable)
                        }.toSet()
                        val initializer = variable.initializer.orElse(null) ?: return@variables
                        val factories = initializer.findAll(MethodReferenceExpr::class.java).mapNotNull { reference ->
                            if (reference.identifier != "new" || reference.scope !is TypeExpr) return@mapNotNull null
                            index.expressionType(reference.scope, reference)
                        }.distinct()
                        factories.forEach factories@{ blockEntityType ->
                            val carriesExactType = blockEntityType in declaredTypes &&
                                (registryFieldType == "com.tterrag.registrate.util.entry.BlockEntityEntry" ||
                                    BLOCK_ENTITY_TYPE in declaredTypes)
                            if (!carriesExactType) return@factories
                            val owner = exactEnclosingNamedClass(variable)
                                ?.fullyQualifiedName
                                ?.orElse(null)
                                ?: throw IllegalStateException(
                                    "Cannot resolve owner of BlockEntity registry field ${variable.nameAsString}"
                                )
                            references.getOrPut(blockEntityType) { linkedSetOf() } +=
                                "$owner.${variable.nameAsString}.get()"
                        }
                    }
                }
            }
            return ExactBlockEntityRegistryGraph(
                references.mapValues { (_, values) -> values.toList() },
                index
            )
        }
    }
}
