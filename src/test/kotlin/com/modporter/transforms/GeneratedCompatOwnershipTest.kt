package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommonNeoForge121Apis
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class GeneratedCompatOwnershipTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `common source migrations preserve the owned native portal helper bytes`() {
        val path = directory.resolve("src/main/java/com/modporter/generated/example/compat/LegacyPortalProbe.java")
        path.parent.createDirectories()
        val template = javaClass.getResource("/compat/LegacyPortalProbe.java.template")!!.readText()
            .replace("@PACKAGE@", "com.modporter.generated.example.compat")
        path.writeText(template)
        StructuralRefactorPass().migrateCommonNeoForge121Apis(directory, false)
        assertEquals(template, path.readText())
    }
}
