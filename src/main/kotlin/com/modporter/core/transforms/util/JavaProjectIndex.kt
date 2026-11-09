package com.modporter.core.transforms.util

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * The top-level and nested type declarations of a project's Java sources, keyed by simple name,
 * with their direct supertypes, so rules can walk a hierarchy (every packet class under a
 * packet base, for example) without resolving the full classpath.
 */
class JavaProjectIndex private constructor(private val declarations: Map<String, Declaration>) {
    data class Declaration(
        val name: String,
        val file: Path,
        val kind: String,
        val isAbstract: Boolean,
        val directSuper: String?,
        val interfaces: List<String>,
        val typeParameters: String
    )

    fun declaration(simpleName: String): Declaration? = declarations[simpleName]

    /** The declarations found in [file], outermost first. */
    fun declarationsIn(file: Path): List<Declaration> = declarations.values.filter { it.file == file }

    /** Simple names of every declaration that extends [baseSimpleName] directly or transitively. */
    fun subtypesOf(baseSimpleName: String): List<Declaration> {
        val result = linkedMapOf<String, Declaration>()
        var frontier = setOf(baseSimpleName)
        while (frontier.isNotEmpty()) {
            val next = declarations.values
                .filter { declaration -> declaration.name !in result && (declaration.directSuper in frontier || declaration.interfaces.any { it in frontier }) }
            next.forEach { result[it.name] = it }
            frontier = next.map { it.name }.toSet()
        }
        return result.values.toList()
    }

    companion object {
        private val declarationPattern = Regex(
            """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*((?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)(\s*<[^{]*?>)?(?:\s*\([^)]*\))?(?:\s+extends\s+([\w.$]+)(?:<[^{]*?>)?)?(?:\s+implements\s+([^{]+?))?\s*(?:permits\s+[^{]+?)?\{"""
        )

        fun build(srcDir: Path): JavaProjectIndex {
            val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
                paths.filter { it.extension == "java" }.toList()
            }
            return build(files)
        }

        fun build(files: List<Path>): JavaProjectIndex {
            val declarations = linkedMapOf<String, Declaration>()
            files.forEach { file ->
                val source = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                declarationPattern.findAll(masked).forEach { match ->
                    val modifiers = match.groupValues[1]
                    val name = match.groupValues[3]
                    val interfaces = match.groupValues[6]
                        .split(',')
                        .map { it.trim().substringBefore('<').substringAfterLast('.') }
                        .filter { it.isNotEmpty() }
                    declarations.putIfAbsent(
                        name,
                        Declaration(
                            name = name,
                            file = file,
                            kind = match.groupValues[2],
                            isAbstract = modifiers.contains("abstract") || match.groupValues[2] == "interface",
                            directSuper = match.groupValues[5].takeIf { it.isNotEmpty() }?.substringAfterLast('.'),
                            interfaces = interfaces,
                            typeParameters = match.groupValues[4].trim()
                        )
                    )
                }
            }
            return JavaProjectIndex(declarations)
        }
    }
}
