package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList

internal fun StructuralRefactorPass.validateNoUnresolvedEventBusSubscriberModBusParameters(
    projectDir: Path
) {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return
    val offenders = Files.walk(srcDir)
        .filter { it.extension == "java" }
        .filter { containsExecutableEventBusSubscriberModBusParameter(it.readText()) }
        .map { projectDir.relativize(it).toString().replace('\\', '/') }
        .sorted()
        .toList()
    if (offenders.isNotEmpty()) {
        throw IllegalStateException(
            "@EventBusSubscriber(bus=MOD) remains without a proven explicit mod-event-bus registration: " +
                offenders.joinToString()
        )
    }
}

private fun StructuralRefactorPass.containsExecutableEventBusSubscriberModBusParameter(
    source: String
): Boolean {
    val busExpression = Regex(
        """\bbus\s*=\s*(?:(?:Mod\.)?EventBusSubscriber\.)?Bus\.MOD\b"""
    )
    return executableEventBusSubscriberAnnotationRanges(source).any { range ->
        busExpression.containsMatchIn(maskJavaCommentsAndLiterals(source.substring(range.first, range.last + 1)))
    }
}

internal fun StructuralRefactorPass.executableEventBusSubscriberAnnotationRanges(
    source: String
): List<IntRange> {
    val executable = maskJavaCommentsAndLiterals(source)
    val annotation = Regex("""@(?:Mod\.)?EventBusSubscriber\b""")
    return annotation.findAll(executable).map { match ->
        var cursor = match.range.last + 1
        while (cursor < executable.length && executable[cursor].isWhitespace()) cursor++
        if (cursor < executable.length && executable[cursor] == '(') {
            val close = findMatchingParen(executable, cursor)
            if (close < 0) {
                throw IllegalStateException("Cannot match @EventBusSubscriber annotation arguments")
            }
            match.range.first..close
        } else {
            match.range
        }
    }.toList()
}
