package com.modporter.core.transforms.structural

/** Exact public type contracts for third-party APIs that are absent from the source project. */
internal object ExactExternalTypeContracts {
    data class RecordComponentSurface(
        val importOwner: String,
        val sourceType: String,
        val qualifiedType: String,
        val components: Set<String>
    )

    data class PublicMemberTypeSurface(
        val declaringOwner: String,
        val simpleName: String,
        val qualifiedType: String
    )

    // Positive public-member evidence from both Ponder SDKs; unlisted names remain unknown.
    // Ponder-Forge-1.20.1:1.0.91 SHA256 86e6b64372aba6d9c56f2c35725ea26d8febf2c75eed9950566e7f2849443b34
    // ponder-neoforge:1.0.87+mc1.21.1 SHA256 d6b7fdf94359f73dc1fee730eded898d51b3bf0d626710753253becd8e4e6bef
    val publicMemberTypeSurfaces = listOf(
        "PonderOverlayInstructions",
        "PonderSpecialInstructions",
        "PonderWorldInstructions",
        "PonderDebugInstructions",
        "PonderEffectInstructions"
    ).map { simpleName ->
        PublicMemberTypeSurface(
            declaringOwner = PONDER_SCENE_BUILDER,
            simpleName = simpleName,
            qualifiedType = "$PONDER_SCENE_BUILDER.$simpleName"
        )
    }

    private val publicMemberTypesByOwnerAndName = publicMemberTypeSurfaces.associate { surface ->
        (surface.declaringOwner to surface.simpleName) to surface.qualifiedType
    }
    private val publicMemberTypeNames = publicMemberTypeSurfaces.map { it.qualifiedType }.toSet()

    fun publicMemberType(declaringOwner: String, simpleName: String): String? =
        publicMemberTypesByOwnerAndName[declaringOwner to simpleName]

    fun isPublicMemberType(qualifiedType: String): Boolean = qualifiedType in publicMemberTypeNames

    val assignableTypes = mapOf(
        "net.createmod.ponder.api.level.PonderLevel" to setOf(
            "net.createmod.catnip.levelWrappers.SchematicLevel",
            "net.createmod.catnip.levelWrappers.WrappedLevel",
            "net.minecraft.world.level.Level"
        ),
        "net.createmod.catnip.levelWrappers.SchematicLevel" to setOf(
            "net.createmod.catnip.levelWrappers.WrappedLevel",
            "net.minecraft.world.level.Level"
        ),
        "net.createmod.catnip.levelWrappers.WrappedLevel" to setOf(
            "net.minecraft.world.level.Level"
        )
    )

    val recordComponentSurfaces = listOf(
        RecordComponentSurface(
            importOwner = "com.dtteam.dynamictrees.block.branch.TrunkShellBlock",
            sourceType = "TrunkShellBlock.ShellMuse",
            qualifiedType = "com.dtteam.dynamictrees.block.branch.TrunkShellBlock.ShellMuse",
            components = setOf("state", "pos", "museOffset")
        )
    )

    private val closedMethodSurfaces = mapOf(
        "net.createmod.ponder.api.VirtualBlockEntity" to setOf(
            MethodSignature("isVirtual", emptyList()),
            MethodSignature("markVirtual", emptyList())
        )
    )

    fun hasClosedMethodSurface(owner: String): Boolean = owner in closedMethodSurfaces

    fun containsMethod(owner: String, name: String, parameterTypes: List<String>): Boolean =
        MethodSignature(name, parameterTypes) in closedMethodSurfaces[owner].orEmpty()

    private data class MethodSignature(
        val name: String,
        val parameterTypes: List<String>
    )

    private const val PONDER_SCENE_BUILDER =
        "net.createmod.ponder.foundation.PonderSceneBuilder"
}
