package com.modporter.core.transforms.shared

import java.security.MessageDigest

/** Canonical templates and whole-closure identity for the shared legacy-plant runtime. */
internal object LegacyPlantRuntimeResources {
    val JAVA_NAMES: Set<String> = linkedSetOf(
        "PlantType",
        "IPlantable",
        "LegacyPlantSustainer",
        "LegacyPlantPlacement",
        "LegacyPlantableAdapter",
        "LegacySoilAdapter",
        "LegacyPlantPlacementAdapter",
        "LegacyPlantables",
        "LegacyPlantAdapterProfile",
        "ReviewedExternalPlantAdapters",
        "LegacyPlantMixinPlugin",
        "LegacyPlantMushroomRotCompat",
        "mixin/LegacyPlantMushroomRotMixin",
        "mixin/LegacyPlantPluginSentinel"
    )

    private const val RUNTIME_PACKAGE = "com.modporter.compat.plant.v1"
    private const val MIXIN_CONFIG = "modporter-legacy-plant-dt.mixins.json"

    fun javaTemplates(): Map<String, String> = JAVA_NAMES.associateWith { name ->
        requiredResourceBytes("/compat/$name.java.template").toString(Charsets.UTF_8)
    }

    fun resourceTemplates(): Map<String, String> = resourceTemplateBytes()
        .mapValues { (_, bytes) -> bytes.toString(Charsets.UTF_8) }

    fun canonicalSourceHashText(javaTemplates: Map<String, String>): String =
        canonicalSourceHash(
            javaTemplates.mapValues { (_, content) -> content.toByteArray(Charsets.UTF_8) }
        )

    fun canonicalSourceHashBytes(javaTemplates: Map<String, ByteArray>): String =
        canonicalSourceHash(javaTemplates)

    fun expandedResources(sourceSha: String): Map<String, String> {
        require(sourceSha.matches(Regex("[0-9a-f]{64}"))) {
            "Malformed canonical legacy plant runtime source hash"
        }
        return resourceTemplates().mapValues { (_, template) ->
            template
                .replace("@PACKAGE@", RUNTIME_PACKAGE)
                .replace("@RUNTIME_SOURCE_SHA256@", sourceSha)
        }
    }

    private fun canonicalSourceHash(javaTemplates: Map<String, ByteArray>): String {
        require(javaTemplates.keys == JAVA_NAMES) {
            "Canonical legacy plant runtime Java source inventory mismatch"
        }
        val entries = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in javaTemplates) {
            entries[name] = normalizeJavaTemplate(bytes)
        }
        for ((name, bytes) in resourceTemplateBytes()) {
            entries["resource/$name"] = bytes
        }

        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in entries.toSortedMap()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeJavaTemplate(bytes: ByteArray): ByteArray =
        bytes.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .toByteArray(Charsets.UTF_8)

    private fun resourceTemplateBytes(): Map<String, ByteArray> = linkedMapOf(
        MIXIN_CONFIG to requiredResourceBytes("/compat/$MIXIN_CONFIG.template")
    )

    private fun requiredResourceBytes(path: String): ByteArray = checkNotNull(
        LegacyPlantRuntimeResources::class.java.getResource(path)
    ) {
        "Missing canonical legacy plant runtime resource $path"
    }.openStream().use { it.readBytes() }
}
