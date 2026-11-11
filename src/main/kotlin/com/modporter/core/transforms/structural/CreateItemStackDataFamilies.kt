package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Repairs the source-owned Create 0.5 item-data families after the generic 1.20.1 -> 1.21.1
 * ItemStack/NBT and HolderLookup rewrites have run.
 *
 * These classes share one migration boundary: legacy stack NBT contains nested ItemStacks and
 * therefore either needs the caller's real registry provider or a native typed component. The
 * migration never invents a built-in/null provider, never drops an unknown CUSTOM_DATA sibling,
 * and keeps legacy CUSTOM_DATA reads where old saves still need them.
 *
 * [lazyFilterCallerProof] must come from a project-wide scan. It is true only when no source
 * outside FilterItemStack reads or escapes ListFilterItemStack.containedItems before test(Level,
 * ...) supplies a provider. The one-argument wrapper remains source-compatible, while provider
 * reads initialize eagerly and provider-less wrappers initialize once on first test.
 */
internal object CreateItemStackDataFamilies {
    private const val CREATE = "com.simibubi.create"
    private const val FILTER_ITEM = "$CREATE.content.logistics.filter.FilterItem"
    private const val LIST_FILTER_ITEM = "$CREATE.content.logistics.filter.ListFilterItem"
    private const val FILTER_ITEM_STACK = "$CREATE.content.logistics.filter.FilterItemStack"
    private const val CLIPBOARD_ENTRY = "$CREATE.content.equipment.clipboard.ClipboardEntry"
    private const val PACKAGE_PORT_BLOCK_ENTITY =
        "$CREATE.content.logistics.packagePort.PackagePortBlockEntity"

    private val filterTransactionOwners = setOf(
        FILTER_ITEM,
        LIST_FILTER_ITEM,
        FILTER_ITEM_STACK
    )

    private data class ClipboardCallShape(
        val legacy: Regex,
        val migrated: Regex
    )

    private val clipboardCallers = mapOf(
        "$CREATE.content.equipment.clipboard.ClipboardScreen" to ClipboardCallShape(
            Regex("""ClipboardEntry\s*\.\s*saveAll\s*\(\s*pages\s*,\s*item\s*\)"""),
            Regex(
                """ClipboardEntry\s*\.\s*saveAll\s*\(\s*pages\s*,\s*item\s*,\s*""" +
                    """(?:minecraft|getMinecraft\s*\(\s*\))\s*\.\s*level\s*\.\s*""" +
                    """registryAccess\s*\(\s*\)\s*\)"""
            )
        ),
        "$CREATE.content.equipment.clipboard.ClipboardValueSettingsHandler" to ClipboardCallShape(
            Regex("""ClipboardEntry\s*\.\s*saveAll\s*\(\s*listTo\s*,\s*itemStack\s*\)"""),
            Regex(
                """ClipboardEntry\s*\.\s*saveAll\s*\(\s*listTo\s*,\s*itemStack\s*,\s*""" +
                    """event\s*\.\s*getEntity\s*\(\s*\)\s*\.\s*registryAccess\s*\(\s*\)\s*\)"""
            )
        ),
        "$CREATE.content.schematics.cannon.MaterialChecklist" to ClipboardCallShape(
            Regex("""ClipboardEntry\s*\.\s*saveAll\s*\(\s*pages\s*,\s*clipboard\s*\)"""),
            Regex(
                """ClipboardEntry\s*\.\s*saveAll\s*\(\s*pages\s*,\s*clipboard\s*,\s*""" +
                    """registries\s*\)"""
            )
        ),
        PACKAGE_PORT_BLOCK_ENTITY to ClipboardCallShape(
            Regex("""ClipboardEntry\s*\.\s*saveAll\s*\(\s*list\s*,\s*mainHandItem\s*\)"""),
            Regex(
                """ClipboardEntry\s*\.\s*saveAll\s*\(\s*list\s*,\s*mainHandItem\s*,\s*""" +
                    """player\s*\.\s*registryAccess\s*\(\s*\)\s*\)"""
            )
        )
    )

    private val ownedOwners = setOf(
        "$CREATE.content.equipment.blueprint.BlueprintItem",
        FILTER_ITEM,
        LIST_FILTER_ITEM,
        FILTER_ITEM_STACK,
        "$CREATE.content.logistics.filter.FilterMenu",
        "$CREATE.content.logistics.filter.AttributeFilterMenu",
        "$CREATE.content.logistics.box.PackageItem",
        CLIPBOARD_ENTRY,
        "$CREATE.content.equipment.clipboard.ClipboardScreen",
        "$CREATE.content.equipment.clipboard.ClipboardEditPacket",
        "$CREATE.content.equipment.clipboard.ClipboardValueSettingsHandler",
        "$CREATE.content.equipment.clipboard.ClipboardBlockEntity",
        "$CREATE.content.schematics.cannon.MaterialChecklist",
        "$CREATE.content.equipment.sandPaper.SandPaperItem",
        "$CREATE.content.logistics.packagerLink.LogisticallyLinkedBlockItem",
        PACKAGE_PORT_BLOCK_ENTITY
    )

    fun migrate(source: String, lazyFilterCallerProof: Boolean): String {
        if (!targetContractsAvailable()) return source
        return when (canonicalOwner(source)) {
            "$CREATE.content.equipment.blueprint.BlueprintItem" -> migrateBlueprintItem(source)
            "$CREATE.content.logistics.filter.FilterItem" -> migrateFilterItem(source)
            "$CREATE.content.logistics.filter.ListFilterItem" ->
                if (lazyFilterCallerProof) migrateListFilterItem(source) else source
            "$CREATE.content.logistics.filter.FilterItemStack" ->
                if (lazyFilterCallerProof) migrateFilterItemStack(source) else source
            "$CREATE.content.logistics.filter.FilterMenu" -> migrateFilterMenu(source)
            "$CREATE.content.logistics.filter.AttributeFilterMenu" -> migrateAttributeFilterMenu(source)
            "$CREATE.content.logistics.box.PackageItem" -> migratePackageItem(source)
            "$CREATE.content.equipment.clipboard.ClipboardEntry" -> migrateClipboardEntry(source)
            "$CREATE.content.equipment.clipboard.ClipboardScreen" -> migrateClipboardScreen(source)
            "$CREATE.content.equipment.clipboard.ClipboardEditPacket" -> migrateClipboardEditPacket(source)
            "$CREATE.content.equipment.clipboard.ClipboardValueSettingsHandler" ->
                migrateClipboardValueSettingsHandler(source)
            "$CREATE.content.equipment.clipboard.ClipboardBlockEntity" -> migrateClipboardBlockEntity(source)
            "$CREATE.content.schematics.cannon.MaterialChecklist" -> migrateMaterialChecklist(source)
            "$CREATE.content.equipment.sandPaper.SandPaperItem" -> migrateSandPaperItem(source)
            "$CREATE.content.logistics.packagerLink.LogisticallyLinkedBlockItem" ->
                migrateLogisticallyLinkedBlockItem(source)
            PACKAGE_PORT_BLOCK_ENTITY -> migratePackagePortBlockEntity(source)
            else -> source
        }
    }

    /**
     * Stages the complete source-owned item-data family in memory and publishes it only after every
     * active cross-file signature and caller is closed. Returning [sources] by identity means that
     * no admitted project transaction was available; callers must not fall back to per-file writes.
     */
    fun migrateProject(sources: Map<String, String>): Map<String, String> {
        if (!targetContractsAvailable()) return sources

        val indexed = LinkedHashMap<String, Pair<String, String>>()
        for ((path, source) in sources) {
            val owner = canonicalOwner(source) ?: continue
            if (owner !in ownedOwners) continue
            if (indexed.put(owner, path to source) != null) return sources
        }
        if (indexed.isEmpty()) return sources

        val activeFilterOwners = indexed.keys.intersect(filterTransactionOwners)
        val filterActive = activeFilterOwners.isNotEmpty()
        if (filterActive) {
            if (activeFilterOwners != filterTransactionOwners) return sources
            if (!hasLazyFilterCallerProof(sources)) return sources
        }

        val clipboardActive = CLIPBOARD_ENTRY in indexed ||
            sources.values.any {
                Regex("""\bClipboardEntry\s*(?:::|\.\s*saveAll)""")
                    .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(it))
            }
        if (clipboardActive) {
            if (CLIPBOARD_ENTRY !in indexed) return sources
            if (!clipboardFamilyClosed(sources, requireMigrated = false)) return sources
        }

        val staged = LinkedHashMap(sources)
        for ((owner, pathAndSource) in indexed) {
            val (path, source) = pathAndSource
            val migrated = migrate(source, lazyFilterCallerProof = true)
            if (migrated == source && !isMigratedOwner(owner, source)) return sources
            staged[path] = migrated
        }

        if (filterActive && !filterFamilyMigrated(staged)) return sources
        if (clipboardActive && !clipboardFamilyClosed(staged, requireMigrated = true)) return sources
        return if (staged == sources) sources else staged
    }

    /**
     * True when this project still exposes an owned legacy or incomplete cross-file family. The
     * filesystem adapter uses this to distinguish a clean no-op from a failed admission that must
     * be reported as an automated gate.
     */
    fun hasPendingProjectMigration(sources: Map<String, String>): Boolean {
        val owned = sources.values.mapNotNull { source ->
            canonicalOwner(source)?.takeIf { it in ownedOwners }?.let { it to source }
        }
        if (owned.any { (owner, source) -> !isMigratedOwner(owner, source) }) return true

        val hasClipboardSurface = sources.values.any { source ->
            canonicalOwner(source) == CLIPBOARD_ENTRY ||
                Regex("""\bClipboardEntry\s*(?:::|\.\s*saveAll)""")
                    .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
        }
        return hasClipboardSurface &&
            !clipboardFamilyClosed(sources, requireMigrated = true)
    }

    /**
     * Complete caller/escape admission for the lazy list-filter bridge. Comments and literals are
     * masked. The declaration and all reads in FilterItemStack itself are allowed; any other
     * project reference to `containedItems` rejects the bridge.
     */
    fun hasLazyFilterCallerProof(sources: Map<String, String>): Boolean {
        val ownerPath = "com/simibubi/create/content/logistics/filter/FilterItemStack.java"
        val owner = sources.entries.singleOrNull {
            it.key.replace('\\', '/').endsWith(ownerPath)
        } ?: return false
        if (canonicalOwner(owner.value) != "$CREATE.content.logistics.filter.FilterItemStack") return false

        var declarationFound = false
        for ((path, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val references = Regex("""(?<![\w$])containedItems(?![\w$])""").findAll(masked).toList()
            if (references.isEmpty()) continue
            if (path != owner.key) return false
            declarationFound = references.any { match ->
                val prefix = masked.substring(maxOf(0, match.range.first - 64), match.range.first)
                Regex("""public\s+List\s*<\s*FilterItemStack\s*>\s*$""").containsMatchIn(prefix)
            }
            val admitted = references.all { match ->
                val prefix = masked.substring(maxOf(0, match.range.first - 96), match.range.first)
                val suffix = masked.substring(match.range.last + 1, minOf(masked.length, match.range.last + 48))
                Regex("""public\s+List\s*<\s*FilterItemStack\s*>\s*$""").containsMatchIn(prefix) ||
                    Regex("""\bfor\s*\([^)]*:\s*$""").containsMatchIn(prefix) ||
                    Regex("""^\s*=\s*new\s+ArrayList\s*<>\s*\(\s*\)""").containsMatchIn(suffix) ||
                    Regex("""^\s*\.\s*add\s*\(""").containsMatchIn(suffix)
            }
            if (!admitted) return false
        }
        return declarationFound
    }

    private fun migrateBlueprintItem(source: String): String {
        val pattern = Regex("""getFilterItemHandler\(\s*result\s*\)""")
        if (pattern.findAll(JavaSourceText.maskCommentsAndLiterals(source)).count() != 2) return source
        return pattern.replace(source, "getFilterItemHandler(result, registries)")
    }

    private fun migrateFilterItem(source: String): String {
        var result = source
        result = replaceOne(
            result,
            Regex(
                """(?s)public static boolean doPackagesHaveSameData\(@NotNull ItemStack a, @NotNull ItemStack b\) \{.*?\n\t\}\n\n\tpublic abstract FilterItemStack makeStackWrapper"""
            ),
            """public static boolean doPackagesHaveSameData(@NotNull ItemStack a, @NotNull ItemStack b) {
		if (a.isEmpty() || a.has(DataComponents.CUSTOM_DATA) != b.has(DataComponents.CUSTOM_DATA))
			return false;
		if (!haveSameNonCustomComponents(a, b))
			return false;
		if (!a.has(DataComponents.CUSTOM_DATA))
			return true;

		CompoundTag aTag = a.getOrDefault(DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
		CompoundTag bTag = b.getOrDefault(DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
		return packageCustomDataMatches(aTag, bTag) && packageCustomDataMatches(bTag, aTag);
	}

	private static boolean haveSameNonCustomComponents(ItemStack a, ItemStack b) {
		for (net.minecraft.core.component.TypedDataComponent<?> component : a.getComponents()) {
			net.minecraft.core.component.DataComponentType<?> type = component.type();
			if (type == DataComponents.CUSTOM_DATA)
				continue;
			if (!Objects.equals(a.get(type), b.get(type)))
				return false;
		}
		for (net.minecraft.core.component.TypedDataComponent<?> component : b.getComponents()) {
			net.minecraft.core.component.DataComponentType<?> type = component.type();
			if (type == DataComponents.CUSTOM_DATA)
				continue;
			if (!Objects.equals(a.get(type), b.get(type)))
				return false;
		}
		return true;
	}

	private static boolean packageCustomDataMatches(CompoundTag expected, CompoundTag actual) {
		for (String key : expected.getAllKeys()) {
			if (key.equals("Fragment"))
				continue;
			if (!Objects.equals(expected.get(key), actual.get(key)))
				return false;
		}
		return true;
	}

	public abstract FilterItemStack makeStackWrapper"""
        ) ?: return source
        result = insertBeforeOne(
            result,
            Regex(
                """(?m)^([ \t]*)public abstract FilterItemStack makeStackWrapper\(ItemStack filter\);"""
            ),
            "\tpublic FilterItemStack makeStackWrapper(ItemStack filter, " +
                "HolderLookup.Provider registries) {\n" +
                "\t\treturn makeStackWrapper(filter);\n" +
                "\t}\n\n"
        ) ?: return source
        result = JavaSourceText.addImport(result, "net.minecraft.nbt.CompoundTag")
        return result
    }

    private fun migrateListFilterItem(source: String): String {
        var result = source
        if (!result.contains("makeStackWrapper(ItemStack filter, HolderLookup.Provider registries)")) {
            val anchor = Regex(
                """(?s)(@Override\s+public FilterItemStack makeStackWrapper\(ItemStack filter\)\s*\{\s*return new ListFilterItemStack\(filter\);\s*\})"""
            )
            result = insertAfterOne(
                result,
                anchor,
                """

	@Override
	public FilterItemStack makeStackWrapper(ItemStack filter, HolderLookup.Provider registries) {
		return new ListFilterItemStack(filter, registries);
	}
"""
            ) ?: return source
        }
        if (!result.contains(
                "getFilterItemHandler(ItemStack stack, HolderLookup.Provider registries)"
            )
        ) {
            val handler = Regex(
                """(?s)(\tpublic ItemStackHandler getFilterItemHandler\(ItemStack stack\) \{.*?\r?\n\t\})"""
            )
            result = insertAfterOne(
                result,
                handler,
                """

	public ItemStackHandler getFilterItemHandler(ItemStack stack, HolderLookup.Provider registries) {
		ItemStackHandler newInv = new ItemStackHandler(18);
		CompoundTag invNBT = stack.getOrDefault(
			net.minecraft.core.component.DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound("Items");
		if (!invNBT.isEmpty())
			newInv.deserializeNBT(registries, invNBT);
		return newInv;
	}
"""
            ) ?: return source
        }
        result = JavaSourceText.addImport(result, "net.minecraft.core.HolderLookup")
        return result
    }
    private fun migrateFilterItemStack(source: String): String {
        if (filterItemStackMigrated(source)) return source

        var result = replaceOne(
            source,
            Regex(
                """(?m)^\s*public static FilterItemStack of\(CompoundTag tag(?:, HolderLookup\.Provider registries)?\)"""
            ),
            """
	public static FilterItemStack of(ItemStack filter, HolderLookup.Provider registries) {
		if (filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
			&& filter.getItem() instanceof FilterItem item) {
			trimFilterTag(filter);
			return item.makeStackWrapper(filter, registries);
		}

		return new FilterItemStack(filter);
	}

	public static FilterItemStack of(CompoundTag tag, HolderLookup.Provider registries)"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """return of\(\s*(?:ItemStack\.of\(\s*tag\s*\)|ItemStack\.parseOptional\(\s*registries\s*,\s*tag\s*\))\s*\);"""
            ),
            "return of(ItemStack.parseOptional(registries, tag), registries);"
        ) ?: return source

        val constructor = Regex(
            """(?s)\t\tpublic ListFilterItemStack\(ItemStack filter(?:, HolderLookup\.Provider registries)?\) \{.*?""" +
                """\r?\n\t\t\}\s*\r?\n\t\t@Override\s*\r?\n""" +
                """\t\tpublic boolean test\(Level world, ItemStack stack, boolean matchNBT\) \{"""
        )
        val replacement = """
		private final ItemStack filterSnapshot;
		private boolean containedItemsResolved;

		public ListFilterItemStack(ItemStack filter) {
			super(filter);
			filterSnapshot = filter.copy();
			boolean defaults =
				!filterSnapshot.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);

			containedItems = new ArrayList<>();
			containedItemsResolved = false;
			shouldRespectNBT = defaults ? false
				: filterSnapshot.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
					net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
					.getBoolean("RespectNBT");
			isBlacklist = defaults ? false
				: filterSnapshot.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
					net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
					.getBoolean("Blacklist");
		}

		public ListFilterItemStack(ItemStack filter, HolderLookup.Provider registries) {
			this(filter);
			resolveContainedItems(registries);
		}

		private void resolveContainedItems(HolderLookup.Provider registries) {
			if (containedItemsResolved)
				return;
			containedItemsResolved = true;
			ItemStackHandler items =
				((ListFilterItem) filterSnapshot.getItem())
					.getFilterItemHandler(filterSnapshot, registries);
			for (int i = 0; i < items.getSlots(); i++) {
				ItemStack stackInSlot = items.getStackInSlot(i);
				if (!stackInSlot.isEmpty())
					containedItems.add(FilterItemStack.of(stackInSlot, registries));
			}
		}

		@Override
		public boolean test(Level world, ItemStack stack, boolean matchNBT) {
			resolveContainedItems(world.registryAccess());
"""
        result = replaceOne(result, constructor, replacement) ?: return source
        result = replaceOne(
            result,
            Regex(
                """(@Override\s+public boolean test\(Level world, FluidStack stack, boolean matchNBT\) \{\s*)for \(FilterItemStack filterItemStack : containedItems\)"""
            ),
            "${'$'}1resolveContainedItems(world.registryAccess());\n\t\t\tfor (FilterItemStack filterItemStack : containedItems)"
        ) ?: return source
        result = JavaSourceText.addImport(result, "net.minecraft.core.HolderLookup")
        return result
    }
    private fun migrateFilterMenu(source: String): String {
        var result = replaceOne(
            source,
            Regex("""getFilterItemHandler\(\s*contentHolder\s*\)"""),
            "getFilterItemHandler(contentHolder, player.registryAccess())"
        ) ?: return source
        val body = Regex(
            """(?s)(@Override\s+protected void saveData\(ItemStack filterItem\)\s*)""" +
                """\{.*?\r?\n\t\}\s*\r?\n\}"""
        )
        result = replaceOne(
            result,
            body,
            """
${'$'}1{
		super.saveData(filterItem);
		CompoundTag tag = filterItem.getOrDefault(DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
		tag.putBoolean("RespectNBT", respectNBT);
		tag.putBoolean("Blacklist", blacklist);

		boolean empty = true;
		for (int i = 0; i < ghostInventory.getSlots(); i++)
			if (!ghostInventory.getStackInSlot(i).isEmpty()) {
				empty = false;
				break;
			}
		if (!respectNBT && !blacklist && empty) {
			tag.remove("Items");
			tag.remove("RespectNBT");
			tag.remove("Blacklist");
		}
		net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, filterItem, tag);
	}

}"""
        ) ?: return source
        return result
    }

    private fun migrateAttributeFilterMenu(source: String): String {
        val body = Regex(
            """(?s)(@Override\s+protected void saveData\(ItemStack filterItem\)\s*)""" +
                """\{.*?\r?\n\t\}\s*\r?\n\}"""
        )
        return replaceOne(
            source,
            body,
            """${'$'}1{
		ListTag attributes = new ListTag();
		selectedAttributes.forEach(at -> {
			if (at == null)
				return;

			CompoundTag compoundNBT = ItemAttribute.saveStatic(at.getFirst());
			compoundNBT.putBoolean("Inverted", at.getSecond());
			attributes.add(compoundNBT);
		});

		CompoundTag tag = filterItem.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
		if (attributes.isEmpty() && whitelistMode == WhitelistMode.WHITELIST_DISJ) {
			tag.remove("WhitelistMode");
			tag.remove("MatchedAttributes");
		} else {
			tag.putInt("WhitelistMode", whitelistMode.ordinal());
			tag.put("MatchedAttributes", attributes);
		}
		net.minecraft.world.item.component.CustomData.set(
			net.minecraft.core.component.DataComponents.CUSTOM_DATA, filterItem, tag);
	}

}"""
        ) ?: source
    }

    private fun migratePackageItem(source: String): String {
        var result = source
        result = replaceOne(
            result,
            Regex(
                """(?s)public static ItemStack containing\(List<ItemStack> stacks\) \{.*?""" +
                    """\r?\n\t\}\s*\r?\n\tpublic static ItemStack containing""" +
                    """\(ItemStackHandler stacks(?:, HolderLookup\.Provider registries)?\) \{.*?\r?\n\t\}"""
            ),
            """
public static ItemStack containing(List<ItemStack> stacks) {
		ItemStackHandler newInv = new ItemStackHandler(9);
		stacks.forEach(s -> ItemHandlerHelper.insertItemStacked(newInv, s, false));
		return containing(newInv);
	}

	public static ItemStack containing(ItemStackHandler stacks) {
		List<ItemStack> contents = new java.util.ArrayList<>(stacks.getSlots());
		for (int slot = 0; slot < stacks.getSlots(); slot++)
			contents.add(stacks.getStackInSlot(slot));
		ItemStack box = PackageStyles.getRandomBox();
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
		return box;
	}

	public static ItemStack containing(ItemStackHandler stacks, HolderLookup.Provider registries) {
		return containing(stacks);
	}"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """(?s)public static ItemStackHandler getContents\(ItemStack box(?:, HolderLookup\.Provider registries)?\) \{.*?\n\t\}"""
            ),
            """
public static ItemStackHandler getContents(ItemStack box, HolderLookup.Provider registries) {
		if (box.has(DataComponents.CONTAINER)) {
			ItemContainerContents contents = box.getOrDefault(DataComponents.CONTAINER,
				ItemContainerContents.EMPTY);
			ItemStackHandler newInv =
				new ItemStackHandler(Math.max(9, contents.getSlots()));
			for (int slot = 0; slot < contents.getSlots(); slot++)
				newInv.setStackInSlot(slot, contents.getStackInSlot(slot));
			return newInv;
		}
		ItemStackHandler newInv = new ItemStackHandler(9);
		CompoundTag invNBT = box.getOrDefault(DataComponents.CUSTOM_DATA,
			net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound("Items");
		if (!invNBT.isEmpty())
			newInv.deserializeNBT(registries, invNBT);
		return newInv;
	}"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""if \(!tag\.contains\("Items", Tag\.TAG_COMPOUND\)\)\s*\r?\n\s*return;"""),
            """if (!pStack.has(DataComponents.CONTAINER) && !tag.contains("Items", Tag.TAG_COMPOUND))
			return;"""
        ) ?: return source
        result = JavaSourceText.addImport(result, "net.minecraft.core.HolderLookup")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.component.ItemContainerContents")
        return result
    }

    private fun migrateClipboardEntry(source: String): String {
        var result = source
        result = replaceZeroOrOne(
            result,
            Regex(
                """(?s)\s*if \(\(tag\)\.isEmpty\(\)\) \{\s*""" +
                    """\(clipboardItem\)\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                    """\} else \{\s*\(clipboardItem\)\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);\s*\}"""
            ),
            ""
        ) ?: return source
        result = replaceZeroOrOne(
            result,
            Regex("""public CompoundTag writeNBT\(HolderLookup\.Provider registries\)"""),
            "public CompoundTag writeNBT()"
        ) ?: return source
        result = replaceZeroOrOne(
            result,
            Regex("""icon\.saveOptional\(registries\)"""),
            "icon.serializeNBT()"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """public static void saveAll\(List<List<ClipboardEntry>> entries, ItemStack clipboardItem\)"""
            ),
            "public static void saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem, HolderLookup.Provider registries)"
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""NBTHelper\.writeCompoundList\(list, ClipboardEntry::writeNBT\)"""),
            "NBTHelper.writeCompoundList(list, entry -> entry.writeNBT(registries))"
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""(\t\ttag\.put\("Pages".*?\n\t\t\}\)\);)\s*\r?\n\t\}""", RegexOption.DOT_MATCHES_ALL),
            """${'$'}1
		net.minecraft.world.item.component.CustomData.set(
			DataComponents.CUSTOM_DATA, clipboardItem, tag);
	}"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""public CompoundTag writeNBT\(\)"""),
            "public CompoundTag writeNBT(HolderLookup.Provider registries)"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """nbt\.putString\("Text", net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(com\.mojang\.serialization\.JsonOps\.INSTANCE, text\)\.getOrThrow\(\)\.toString\(\)\);"""
            ),
            """nbt.putString("Text", Component.Serializer.toJson(text, registries));"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""icon\.serializeNBT\(\)"""),
            "icon.saveOptional(registries)"
        ) ?: return source
        return result
    }

    private fun migrateClipboardScreen(source: String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val legacyCall = Regex("""ClipboardEntry\.saveAll\(\s*pages\s*,\s*item\s*\)""")
        val minecraftCall = Regex(
            """ClipboardEntry\.saveAll\(\s*pages\s*,\s*item\s*,\s*""" +
                """minecraft\.level\.registryAccess\(\s*\)\s*\)"""
        )
        val getMinecraftCall = Regex(
            """ClipboardEntry\.saveAll\(\s*pages\s*,\s*item\s*,\s*""" +
                """getMinecraft\(\s*\)\.level\.registryAccess\(\s*\)\s*\)"""
        )
        val callCount = legacyCall.findAll(masked).count() +
            minecraftCall.findAll(masked).count() + getMinecraftCall.findAll(masked).count()
        if (callCount != 1) return source
        var result = source
        if (legacyCall.containsMatchIn(masked)) {
            result = replaceOne(
                result,
                legacyCall,
                "ClipboardEntry.saveAll(pages, item, minecraft.level.registryAccess())"
            ) ?: return source
        }
        result = replaceOne(
            result,
            Regex("""SharedConstants\.isAllowedChatCharacter\(\s*pCodePoint\s*\)"""),
            "StringUtil.isAllowedChatCharacter(pCodePoint)"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """if \(pages\.isEmpty\(\)\)\s*\r?\n\s*item\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.of\(new CompoundTag\(\)\)\);"""
            ),
            """if (pages.isEmpty())
			net.minecraft.world.item.component.CustomData.update(
				net.minecraft.core.component.DataComponents.CUSTOM_DATA, item, tag -> {
					tag.remove("Pages");
					tag.remove("Type");
					tag.remove("PreviouslyOpenedPage");
					tag.remove("Readonly");
				});"""
        ) ?: return source
        result = result.replace("import net.minecraft.SharedConstants;\n", "")
        result = JavaSourceText.addImport(result, "net.minecraft.util.StringUtil")
        return result
    }

    private fun migrateClipboardEditPacket(source: String): String {
        var result = source
        val generatedCbeWrite = Regex(
            """(?s)net\.minecraft\.Util\.make\(cbe\.dataContainer,\s*modPorterCustomDataOwner\s*->\s*\{\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataValue\s*=\s*data\.isEmpty\(\)\s*\?\s*null\s*:\s*data;\s*""" +
                """if \(modPorterCustomDataValue == null\) \{\s*""" +
                """modPorterCustomDataOwner\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*modPorterCustomDataOwner\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(modPorterCustomDataValue\)\);\s*\}\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedCbeWrite,
            "cbe.dataContainer.setTag(data.isEmpty() ? null : data);"
        ) ?: return source
        val generatedItemWrite = Regex(
            """(?s)net\.minecraft\.Util\.make\(itemStack,\s*modPorterCustomDataOwner\s*->\s*\{\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataValue\s*=\s*data\.isEmpty\(\)\s*\?\s*null\s*:\s*data;\s*""" +
                """if \(modPorterCustomDataValue == null\) \{\s*""" +
                """modPorterCustomDataOwner\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*modPorterCustomDataOwner\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(modPorterCustomDataValue\)\);\s*\}\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedItemWrite,
            "itemStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data.isEmpty() ? null : data));"
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""data = CreateNBTProcessors\.clipboardProcessor\(data\);"""),
            """data = CreateNBTProcessors.clipboardProcessor(data);
			if (data == null)
				data = new CompoundTag();"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""cbe\.dataContainer\.setTag\(data\.isEmpty\(\) \? null : data\);"""),
            "CustomData.set(DataComponents.CUSTOM_DATA, cbe.dataContainer, data);"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """itemStack\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.of\(data\.isEmpty\(\) \? null : data\)\);"""
            ),
            "CustomData.set(DataComponents.CUSTOM_DATA, itemStack, data);"
        ) ?: return source
        result = JavaSourceText.addImport(result, "net.minecraft.core.component.DataComponents")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.component.CustomData")
        return result
    }
    private fun migrateClipboardValueSettingsHandler(source: String): String {
        var result = replaceZeroOrOne(
            source,
            Regex(
                """ClipboardEntry\.saveAll\(\s*listTo\s*,\s*itemStack\s*,\s*""" +
                    """event\.getEntity\(\)\.registryAccess\(\)\s*\)"""
            ),
            "ClipboardEntry.saveAll(listTo, itemStack)"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """mc\.player\.getMainHandItem\(\)\s*\r?\n\s*\.getTagElement\("CopiedValues"\)"""
            ),
            """getCustomDataChild(mc.player.getMainHandItem(), "CopiedValues")"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""ClipboardEntry\.saveAll\(\s*listTo\s*,\s*itemStack\s*\)"""),
            "ClipboardEntry.saveAll(listTo, itemStack, event.getEntity().registryAccess())"
        ) ?: return source
        val cancellation = Regex(
            """(?s)\s*event\.setCanceled\(true\);\s*switch \(event\) \{.*?default\s*->\s*\{\s*\}\s*\}"""
        )
        if (cancellation.findAll(result).count() != 2) return source
        result = cancellation.replace(result, "\n\t\t\tcancelInteraction(event);")
        val helper = """

	private static void cancelInteraction(PlayerInteractEvent event) {
		if (event instanceof PlayerInteractEvent.RightClickBlock rightClick) {
			rightClick.setCanceled(true);
			rightClick.setCancellationResult(InteractionResult.SUCCESS);
		} else if (event instanceof PlayerInteractEvent.LeftClickBlock leftClick) {
			leftClick.setCanceled(true);
		}
	}
"""
        result = insertBeforeOne(
            result,
            Regex("""(?m)^\s*private static CompoundTag getCustomDataChild\("""),
            helper
        ) ?: return source
        return result
    }

    private fun migrateClipboardBlockEntity(source: String): String =
        replaceOne(
            source,
            Regex(
                """dataContainer\.getOrDefault\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\) != null"""
            ),
            "dataContainer.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)"
        ) ?: source

    private fun migrateMaterialChecklist(source: String): String {
        var result = source
        result = replaceZeroOrOne(
            result,
            Regex(
                """CompoundTag tag = \(book\)\.getOrDefault\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\);"""
            ),
            "CompoundTag tag = book.getOrDefault(" +
                "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
        ) ?: return source
        result = replaceZeroOrOne(
            result,
            Regex(
                """CompoundTag tag = \(clipboard\)\.getOrDefault\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\);"""
            ),
            "CompoundTag tag = clipboard.getOrDefault(" +
                "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
        ) ?: return source
        result = replaceZeroOrOne(
            result,
            Regex(
                """ClipboardEntry\.saveAll\(\s*pages\s*,\s*clipboard\s*,\s*registries\s*\)"""
            ),
            "ClipboardEntry.saveAll(pages, clipboard)"
        ) ?: return source

        val generatedBookCopyback = Regex(
            """(?s)if \(\(tag\)\.isEmpty\(\)\) \{\s*""" +
                """\(book\)\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*\(book\)\.set\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);\s*\}"""
        )
        if (codeMatches(result, generatedBookCopyback).isNotEmpty()) {
            result = replaceExactly(result, generatedBookCopyback, 4, "") ?: return source
        }
        val generatedClipboardCopyback = Regex(
            """(?s)if \(\(tag\)\.isEmpty\(\)\) \{\s*""" +
                """\(clipboard\)\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*\(clipboard\)\.set\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);\s*\}"""
        )
        result = replaceZeroOrOne(result, generatedClipboardCopyback, "") ?: return source

        val generatedBookName = Regex(
            """(?s)net\.minecraft\.world\.item\.component\.CustomData\.update\(\s*""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*book,\s*""" +
                """modPorterCustomDataRoot\s*->\s*\{\s*""" +
                """String\s+modPorterCustomDataKey\s*=\s*"display";\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataChild\s*=\s*""" +
                """modPorterCustomDataRoot\.getCompound\(modPorterCustomDataKey\);\s*""" +
                """modPorterCustomDataChild\.putString\("Name",\s*""" +
                """net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(\s*""" +
                """com\.mojang\.serialization\.JsonOps\.INSTANCE,\s*textComponent\)""" +
                """\.getOrThrow\(\)\.toString\(\)\);\s*""" +
                """modPorterCustomDataRoot\.put\(modPorterCustomDataKey,\s*""" +
                """modPorterCustomDataChild\);\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedBookName,
            """book.getOrCreateTagElement("display")
			.putString("Name", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString());"""
        ) ?: return source
        val generatedClipboardName = Regex(
            """(?s)net\.minecraft\.world\.item\.component\.CustomData\.update\(\s*""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*clipboard,\s*""" +
                """modPorterCustomDataRoot\s*->\s*\{\s*""" +
                """String\s+modPorterCustomDataKey\s*=\s*"display";\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataChild\s*=\s*""" +
                """modPorterCustomDataRoot\.getCompound\(modPorterCustomDataKey\);\s*""" +
                """modPorterCustomDataChild\.putString\("Name",\s*""" +
                """net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(\s*""" +
                """com\.mojang\.serialization\.JsonOps\.INSTANCE,\s*""" +
                """CreateLang\.translateDirect\("materialChecklist"\)\s*""" +
                """\.setStyle\(Style\.EMPTY\.withItalic\(Boolean\.FALSE\)\)\)""" +
                """\.getOrThrow\(\)\.toString\(\)\);\s*""" +
                """modPorterCustomDataRoot\.put\(modPorterCustomDataKey,\s*""" +
                """modPorterCustomDataChild\);\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedClipboardName,
            """clipboard.getOrCreateTagElement("display")
			.putString("Name", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, CreateLang.translateDirect("materialChecklist")
				.setStyle(Style.EMPTY.withItalic(Boolean.FALSE))).getOrThrow().toString());"""
        ) ?: return source

        val generatedBookWrite = Regex(
            """(?s)net\.minecraft\.Util\.make\(book,\s*modPorterCustomDataOwner\s*->\s*\{\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataValue\s*=\s*tag;\s*""" +
                """if \(modPorterCustomDataValue == null\) \{\s*""" +
                """modPorterCustomDataOwner\.remove\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*modPorterCustomDataOwner\.set\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(""" +
                """modPorterCustomDataValue\)\);\s*\}\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedBookWrite,
            "book.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.of(tag));"
        ) ?: return source
        val generatedClipboardWrite = Regex(
            """(?s)net\.minecraft\.Util\.make\(clipboard,\s*modPorterCustomDataOwner\s*->\s*\{\s*""" +
                """net\.minecraft\.nbt\.CompoundTag\s+modPorterCustomDataValue\s*=\s*tag;\s*""" +
                """if \(modPorterCustomDataValue == null\) \{\s*""" +
                """modPorterCustomDataOwner\.remove\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                """\} else \{\s*modPorterCustomDataOwner\.set\(""" +
                """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                """net\.minecraft\.world\.item\.component\.CustomData\.of\(""" +
                """modPorterCustomDataValue\)\);\s*\}\s*\}\);"""
        )
        result = replaceZeroOrOne(
            result,
            generatedClipboardWrite,
            "clipboard.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.of(tag));"
        ) ?: return source

        result = replaceOne(
            result,
            Regex(
                """\s*CompoundTag tag = book\.getOrDefault\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\);\s*\r?\n\s*ListTag pages = new ListTag\(\);"""
            ),
            "\n\t\tList<Filterable<Component>> pages = new ArrayList<>();"
        ) ?: return source
        val pageWrite = Regex(
            """pages\.add\(StringTag\.valueOf\(net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(com\.mojang\.serialization\.JsonOps\.INSTANCE, textComponent\)\.getOrThrow\(\)\.toString\(\)\)\);"""
        )
        result = replaceExactly(
            result,
            pageWrite,
            4,
            "pages.add(Filterable.passThrough(textComponent));"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """(?s)\s*tag\.put\("pages", pages\);\s*tag\.putBoolean\("readonly", true\);\s*tag\.putString\("author", "Schematicannon"\);\s*tag\.putString\("title", ChatFormatting\.BLUE \+ "Material Checklist"\);"""
            ),
            """

		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
			Filterable.passThrough(ChatFormatting.BLUE + "Material Checklist"),
			"Schematicannon", 0, pages, true));"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """book\.getOrCreateTagElement\("display"\)\s*\.\s*putString\("Name", net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(com\.mojang\.serialization\.JsonOps\.INSTANCE, textComponent\)\.getOrThrow\(\)\.toString\(\)\);\s*\r?\n\s*book\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);"""
            ),
            "book.set(DataComponents.CUSTOM_NAME, textComponent);"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """\s*CompoundTag tag = clipboard\.getOrDefault\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\);"""
            ),
            ""
        ) ?: return source
        result = replaceOne(
            result,
            Regex("""ClipboardEntry\.saveAll\(\s*pages\s*,\s*clipboard\s*\)"""),
            "ClipboardEntry.saveAll(pages, clipboard, registries)"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """clipboard\.getOrCreateTagElement\("display"\)\s*\.\s*putString\("Name", net\.minecraft\.network\.chat\.ComponentSerialization\.CODEC\.encodeStart\(com\.mojang\.serialization\.JsonOps\.INSTANCE, CreateLang\.translateDirect\("materialChecklist"\)\s*\.setStyle\(Style\.EMPTY\.withItalic\(Boolean\.FALSE\)\)\)\.getOrThrow\(\)\.toString\(\)\);"""
            ),
            """clipboard.set(DataComponents.CUSTOM_NAME, CreateLang.translateDirect("materialChecklist")
			.setStyle(Style.EMPTY.withItalic(Boolean.FALSE)));"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """tag\.putBoolean\("Readonly", true\);\s*\r?\n\s*clipboard\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);"""
            ),
            """net.minecraft.world.item.component.CustomData.update(
			DataComponents.CUSTOM_DATA, clipboard, tag -> tag.putBoolean("Readonly", true));"""
        ) ?: return source
        result = JavaSourceText.addImport(result, "net.minecraft.core.component.DataComponents")
        result = JavaSourceText.addImport(result, "net.minecraft.server.network.Filterable")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.component.WrittenBookContent")
        return result
    }

    private fun migrateSandPaperItem(source: String): String {
        var result = replaceZeroOrOne(
            source,
            Regex(
                """(?s)tag\.remove\("Polishing"\);\s*""" +
                    """if \(\(tag\)\.isEmpty\(\)\) \{\s*\(stack\)\.remove\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\);\s*""" +
                    """\} else \{\s*\(stack\)\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);\s*\}\s*""" +
                    """stack\.hurtAndBreak\(1, entityLiving, net\.minecraft\.world\.entity\.LivingEntity\.getSlotForHand\(p\.getUsedItemHand\(\)\)\);"""
            ),
            """tag.remove("Polishing");
			stack.hurtAndBreak(1, entityLiving, net.minecraft.world.entity.LivingEntity.getSlotForHand(p.getUsedItemHand()));"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """tag\.remove\("Polishing"\);\s*\r?\n\s*stack\.hurtAndBreak\(1, entityLiving, net\.minecraft\.world\.entity\.LivingEntity\.getSlotForHand\(p\.getUsedItemHand\(\)\)\);"""
            ),
            """net.minecraft.world.item.component.CustomData.update(
				DataComponents.CUSTOM_DATA, stack, root -> root.remove("Polishing"));
			stack.hurtAndBreak(1, entityLiving,
				net.minecraft.world.entity.LivingEntity.getSlotForHand(entityLiving.getUsedItemHand()));"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """(?s)(@Override\s+public void releaseUsing\(ItemStack stack, Level worldIn, """ +
                    """LivingEntity entityLiving, int timeLeft\)\s*)\{.*?\r?\n\t\}\s*\r?\n\t@Override"""
            ),
            """${'$'}1{
		if (!(entityLiving instanceof Player player))
			return;
		net.minecraft.world.item.component.CustomData.update(
			DataComponents.CUSTOM_DATA, stack, root -> {
				if (!root.contains("Polishing"))
					return;
				ItemStack toPolish =
					ItemStack.parseOptional(worldIn.registryAccess(), root.getCompound("Polishing"));
				player.getInventory()
					.placeItemBackInInventory(toPolish);
				root.remove("Polishing");
			});
	}

	@Override"""
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """stack\.hurtAndBreak\(1, player, net\.minecraft\.world\.entity\.LivingEntity\.getSlotForHand\(p\.getUsedItemHand\(\)\)\);"""
            ),
            """stack.hurtAndBreak(1, player,
					net.minecraft.world.entity.LivingEntity.getSlotForHand(context.getHand()));"""
        ) ?: return source
        result = replaceZeroOrOne(
            result,
            Regex(
                """\s*stack\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.of\(tag\)\);\s*""" +
                    """// After 6 ticks"""
            ),
            "\n\n\t\t// After 6 ticks"
        ) ?: return source
        return result
    }

    private fun migratePackagePortBlockEntity(source: String): String =
        replaceOne(
            source,
            Regex("""ClipboardEntry\.saveAll\(\s*list\s*,\s*mainHandItem\s*\)"""),
            "ClipboardEntry.saveAll(list, mainHandItem, player.registryAccess())"
        ) ?: source

    private fun migrateLogisticallyLinkedBlockItem(source: String): String {
        var result = replaceOne(
            source,
            Regex(
                """return pStack\.has\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\) && pStack\.has\(DataComponents\.BLOCK_ENTITY_DATA\);"""
            ),
            "return pStack.has(DataComponents.BLOCK_ENTITY_DATA);"
        ) ?: return source
        result = replaceOne(
            result,
            Regex(
                """(?s)public static void assignFrequency\(ItemStack stack, Player player, UUID frequency\) \{.*?\n\t\}"""
            ),
            """
public static void assignFrequency(ItemStack stack, Player player, UUID frequency) {
		CompoundTag tag = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
		tag.putUUID("Freq", frequency);

		player.displayClientMessage(CreateLang.translateDirect("logistically_linked.tuned"), true);

		BlockEntity.addEntityType(tag,
			((IBE<?>) ((BlockItem) stack.getItem()).getBlock()).getBlockEntityType());
		stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
	}"""
        ) ?: return source
        result = JavaSourceText.addImport(result, "com.simibubi.create.foundation.block.IBE")
        result = JavaSourceText.addImport(result, "net.minecraft.world.level.block.entity.BlockEntity")
        return result
    }

    private fun clipboardFamilyClosed(
        sources: Map<String, String>,
        requireMigrated: Boolean
    ): Boolean {
        val entrySources = sources.values.filter { canonicalOwner(it) == CLIPBOARD_ENTRY }
        val entry = entrySources.singleOrNull() ?: return false
        val maskedEntry = JavaSourceText.maskCommentsAndLiterals(entry)

        val legacySaveDeclaration = Regex(
            """public\s+static\s+void\s+saveAll\s*\(\s*List\s*<\s*List\s*<\s*""" +
                """ClipboardEntry\s*>\s*>\s+entries\s*,\s*ItemStack\s+clipboardItem\s*\)"""
        )
        val migratedSaveDeclaration = Regex(
            """public\s+static\s+void\s+saveAll\s*\(\s*List\s*<\s*List\s*<\s*""" +
                """ClipboardEntry\s*>\s*>\s+entries\s*,\s*ItemStack\s+clipboardItem\s*,\s*""" +
                """HolderLookup\s*\.\s*Provider\s+registries\s*\)"""
        )
        val legacyWriteDeclaration =
            Regex("""public\s+CompoundTag\s+writeNBT\s*\(\s*\)""")
        val migratedWriteDeclaration = Regex(
            """public\s+CompoundTag\s+writeNBT\s*\(\s*""" +
                """HolderLookup\s*\.\s*Provider\s+registries\s*\)"""
        )
        val legacyWriterReference = Regex("""ClipboardEntry\s*::\s*writeNBT""")
        val migratedWriterReference = Regex(
            """entry\s*->\s*entry\s*\.\s*writeNBT\s*\(\s*registries\s*\)"""
        )

        fun exactLegacyOrMigrated(legacy: Regex, migrated: Regex): Boolean {
            val legacyCount = legacy.findAll(maskedEntry).count()
            val migratedCount = migrated.findAll(maskedEntry).count()
            return if (requireMigrated) {
                legacyCount == 0 && migratedCount == 1
            } else {
                legacyCount + migratedCount == 1
            }
        }

        if (!exactLegacyOrMigrated(legacySaveDeclaration, migratedSaveDeclaration) ||
            !exactLegacyOrMigrated(legacyWriteDeclaration, migratedWriteDeclaration) ||
            !exactLegacyOrMigrated(legacyWriterReference, migratedWriterReference)
        ) {
            return false
        }

        val callStart = Regex("""\bClipboardEntry\s*\.\s*saveAll\s*\(""")
        for (source in sources.values) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val owner = canonicalOwner(source)
            if (owner != CLIPBOARD_ENTRY &&
                (
                    Regex(
                        """\bimport\s+static\s+[\w.]*ClipboardEntry\s*\.\s*(?:saveAll|\*)\s*;"""
                    ).containsMatchIn(masked) ||
                        Regex("""\bClipboardEntry\s*::\s*writeNBT\b""").containsMatchIn(masked) ||
                        (
                            Regex("""\bClipboardEntry\b""").containsMatchIn(masked) &&
                                Regex("""\.\s*writeNBT\s*\(""").containsMatchIn(masked)
                            )
                    )
            ) {
                return false
            }

            val calls = callStart.findAll(masked).count()
            if (calls == 0) continue

            val callerOwner = owner ?: return false
            val shape = clipboardCallers[callerOwner] ?: return false
            val legacyCount = shape.legacy.findAll(masked).count()
            val migratedCount = shape.migrated.findAll(masked).count()
            if (calls != 1 || legacyCount + migratedCount != calls) return false
            if (requireMigrated && (legacyCount != 0 || migratedCount != 1)) return false
        }
        return true
    }

    private fun clipboardScreenMigrated(source: String): Boolean {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val providerCalls = Regex(
            """ClipboardEntry\.saveAll\(\s*pages\s*,\s*item\s*,\s*""" +
                """(?:minecraft|getMinecraft\(\s*\))\.level\.registryAccess\(\s*\)\s*\)"""
        ).findAll(masked).count()
        return providerCalls == 1 &&
            source.contains("StringUtil.isAllowedChatCharacter(pCodePoint)") &&
            source.contains("""tag.remove("Pages")""")
    }
    private fun filterItemStackMigrated(source: String): Boolean {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        fun exactlyOne(pattern: String): Boolean = Regex(pattern).findAll(masked).count() == 1
        return source.contains("private final ItemStack filterSnapshot;") &&
            source.contains("filterSnapshot = filter.copy();") &&
            source.contains("getFilterItemHandler(filterSnapshot, registries)") &&
            source.contains("private void resolveContainedItems(HolderLookup.Provider registries)") &&
            Regex("""resolveContainedItems\(world\.registryAccess\(\)\);""")
                .findAll(masked).count() == 2 &&
            exactlyOne(
                """public static FilterItemStack of\(ItemStack filter, HolderLookup\.Provider registries\)"""
            ) &&
            exactlyOne(
                """public static FilterItemStack of\(CompoundTag tag, HolderLookup\.Provider registries\)"""
            ) &&
            exactlyOne("""public ListFilterItemStack\(ItemStack filter\)""") &&
            exactlyOne(
                """public ListFilterItemStack\(ItemStack filter, HolderLookup\.Provider registries\)"""
            ) &&
            !Regex(
                """return of\(\s*ItemStack\.parseOptional\(\s*registries\s*,\s*tag\s*\)\s*\);"""
            ).containsMatchIn(masked) &&
            !Regex(
                """getFilterItemHandler\(\s*filter\s*,\s*registries\s*\)"""
            ).containsMatchIn(masked)
    }

    private fun filterFamilyMigrated(sources: Map<String, String>): Boolean {
        val filterItem = sourceForOwner(sources, FILTER_ITEM) ?: return false
        val listFilterItem = sourceForOwner(sources, LIST_FILTER_ITEM) ?: return false
        val filterItemStack = sourceForOwner(sources, FILTER_ITEM_STACK) ?: return false
        return filterItem.contains(
            "makeStackWrapper(ItemStack filter, HolderLookup.Provider registries)"
        ) &&
            filterItem.contains("haveSameNonCustomComponents") &&
            listFilterItem.contains(
                "makeStackWrapper(ItemStack filter, HolderLookup.Provider registries)"
            ) &&
            listFilterItem.contains(
                "getFilterItemHandler(ItemStack stack, HolderLookup.Provider registries)"
            ) &&
            listFilterItem.contains("newInv.deserializeNBT(registries, invNBT)") &&
            filterItemStackMigrated(filterItemStack) &&
            hasLazyFilterCallerProof(sources)
    }

    private fun sourceForOwner(sources: Map<String, String>, owner: String): String? =
        sources.values.singleOrNull { canonicalOwner(it) == owner }

    private fun isMigratedOwner(owner: String, source: String): Boolean = when (owner) {
        "$CREATE.content.equipment.blueprint.BlueprintItem" ->
            Regex("""getFilterItemHandler\(\s*result\s*,\s*registries\s*\)""")
                .findAll(JavaSourceText.maskCommentsAndLiterals(source)).count() == 2
        FILTER_ITEM ->
            source.contains("haveSameNonCustomComponents") &&
                source.contains(
                    "makeStackWrapper(ItemStack filter, HolderLookup.Provider registries)"
                )
        LIST_FILTER_ITEM ->
            source.contains(
                "makeStackWrapper(ItemStack filter, HolderLookup.Provider registries)"
            ) &&
                source.contains("new ListFilterItemStack(filter, registries)") &&
                source.contains(
                    "getFilterItemHandler(ItemStack stack, HolderLookup.Provider registries)"
                ) &&
                source.contains("newInv.deserializeNBT(registries, invNBT)")
        FILTER_ITEM_STACK -> filterItemStackMigrated(source)
        "$CREATE.content.logistics.filter.FilterMenu" ->
            source.contains("getFilterItemHandler(contentHolder, player.registryAccess())") &&
                source.contains("CustomData.set(DataComponents.CUSTOM_DATA, filterItem, tag)")
        "$CREATE.content.logistics.filter.AttributeFilterMenu" ->
            source.contains("""tag.remove("MatchedAttributes")""") &&
                source.contains("DataComponents.CUSTOM_DATA, filterItem, tag")
        "$CREATE.content.logistics.box.PackageItem" ->
            source.contains("ItemContainerContents.fromItems(contents)") &&
                source.contains("new ItemStackHandler(Math.max(9, contents.getSlots()))") &&
                source.contains("slot < contents.getSlots()")
        CLIPBOARD_ENTRY ->
            source.contains(
                "saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem, " +
                    "HolderLookup.Provider registries)"
            ) &&
                source.contains("writeNBT(HolderLookup.Provider registries)") &&
                source.contains("icon.saveOptional(registries)") &&
                source.contains("DataComponents.CUSTOM_DATA, clipboardItem, tag") &&
                !source.contains("modPorterCustomDataOwner")
        "$CREATE.content.equipment.clipboard.ClipboardScreen" -> clipboardScreenMigrated(source)
        "$CREATE.content.equipment.clipboard.ClipboardEditPacket" ->
            source.contains("CustomData.set(DataComponents.CUSTOM_DATA, cbe.dataContainer, data)") &&
                source.contains("CustomData.set(DataComponents.CUSTOM_DATA, itemStack, data)")
        "$CREATE.content.equipment.clipboard.ClipboardValueSettingsHandler" ->
            source.contains(
                "ClipboardEntry.saveAll(listTo, itemStack, event.getEntity().registryAccess())"
            ) &&
                source.contains("cancelInteraction(event)") &&
                source.contains(
                    """getCustomDataChild(mc.player.getMainHandItem(), "CopiedValues")"""
                )
        "$CREATE.content.equipment.clipboard.ClipboardBlockEntity" ->
            source.contains(
                "dataContainer.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)"
            )
        "$CREATE.content.schematics.cannon.MaterialChecklist" ->
            source.contains("DataComponents.WRITTEN_BOOK_CONTENT") &&
                source.contains("ClipboardEntry.saveAll(pages, clipboard, registries)") &&
                source.contains("DataComponents.CUSTOM_DATA, clipboard") &&
                !source.contains("modPorterCustomDataRoot") &&
                !source.contains("modPorterCustomDataOwner") &&
                !source.contains("tag.put(\"pages\", pages)")
        "$CREATE.content.equipment.sandPaper.SandPaperItem" ->
            source.contains("entityLiving.getUsedItemHand()") &&
                source.contains("context.getHand()") &&
                source.contains("""root -> root.remove("Polishing")""")
        "$CREATE.content.logistics.packagerLink.LogisticallyLinkedBlockItem" ->
            source.contains("return pStack.has(DataComponents.BLOCK_ENTITY_DATA);") &&
                source.contains("BlockEntity.addEntityType(tag,")
        PACKAGE_PORT_BLOCK_ENTITY ->
            source.contains(
                "ClipboardEntry.saveAll(list, mainHandItem, player.registryAccess())"
            )
        else -> false
    }

    private fun targetContractsAvailable(): Boolean =
        TargetMembers.fieldDescriptor("net.minecraft.core.component.DataComponents", "CUSTOM_DATA") ==
            "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor("net.minecraft.core.component.DataComponents", "CONTAINER") ==
            "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor("net.minecraft.core.component.DataComponents", "BLOCK_ENTITY_DATA") ==
            "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor("net.minecraft.core.component.DataComponents", "WRITTEN_BOOK_CONTENT") ==
            "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor("net.minecraft.core.component.DataComponents", "CUSTOM_NAME") ==
            "Lnet/minecraft/core/component/DataComponentType;" &&
            "(Lnet/minecraft/core/HolderLookup\$Provider;)Lnet/minecraft/nbt/Tag;" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.ItemStack", "saveOptional") &&
            "(Lnet/minecraft/core/HolderLookup\$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/item/ItemStack;" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.ItemStack", "parseOptional") &&
            "()Lnet/minecraft/world/item/ItemStack;" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.ItemStack", "copy") &&
            "()Lnet/minecraft/core/component/DataComponentMap;" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.ItemStack", "getComponents") &&
            "(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/nbt/CompoundTag;)V" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.component.CustomData", "set") &&
            "(Ljava/util/List;)Lnet/minecraft/world/item/component/ItemContainerContents;" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.component.ItemContainerContents", "fromItems") &&
            "()I" in TargetMembers.methodDescriptors(
                "net.minecraft.world.item.component.ItemContainerContents", "getSlots"
            ) &&
            "(I)Lnet/minecraft/world/item/ItemStack;" in TargetMembers.methodDescriptors(
                "net.minecraft.world.item.component.ItemContainerContents", "getStackInSlot"
            ) &&
            "(Lnet/minecraft/server/network/Filterable;Ljava/lang/String;ILjava/util/List;Z)V" in
            TargetMembers.methodDescriptors("net.minecraft.world.item.component.WrittenBookContent", "<init>") &&
            "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/block/entity/BlockEntityType;)V" in
            TargetMembers.methodDescriptors("net.minecraft.world.level.block.entity.BlockEntity", "addEntityType") &&
            "(C)Z" in TargetMembers.methodDescriptors("net.minecraft.util.StringUtil", "isAllowedChatCharacter")

    private fun canonicalOwner(source: String): String? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(masked)?.groupValues?.get(1)
            ?: return null
        val type = Regex(
            """(?m)^\s*public\s+(?:abstract\s+|final\s+)?(?:class|interface|record|enum)\s+([A-Za-z_$][\w$]*)\b"""
        ).find(masked)?.groupValues?.get(1) ?: return null
        return "$pkg.$type"
    }

    private fun replaceOne(source: String, regex: Regex, replacement: String): String? {
        val match = codeMatches(source, regex).singleOrNull() ?: return null
        return source.replaceRange(match.range, match.value.replaceFirst(regex, replacement))
    }

    private fun replaceZeroOrOne(source: String, regex: Regex, replacement: String): String? {
        val matches = codeMatches(source, regex)
        if (matches.size > 1) return null
        val match = matches.singleOrNull() ?: return source
        return source.replaceRange(match.range, match.value.replaceFirst(regex, replacement))
    }

    private fun replaceExactly(
        source: String,
        regex: Regex,
        expectedCount: Int,
        replacement: String
    ): String? {
        val matches = codeMatches(source, regex)
        if (matches.size != expectedCount) return null
        var result = source
        for (match in matches.asReversed()) {
            result = result.replaceRange(match.range, match.value.replaceFirst(regex, replacement))
        }
        return result
    }

    private fun codeMatches(source: String, regex: Regex): List<MatchResult> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return regex.findAll(source).filter { match ->
            val firstCode = match.value.indexOfFirst { !it.isWhitespace() }
            firstCode >= 0 && masked[match.range.first + firstCode] == source[match.range.first + firstCode]
        }.toList()
    }

    private fun insertBeforeOne(source: String, anchor: Regex, insertion: String): String? {
        val matches = anchor.findAll(source).toList()
        if (matches.size != 1) return null
        val at = matches.single().range.first
        return source.substring(0, at) + insertion + source.substring(at)
    }

    private fun insertAfterOne(source: String, anchor: Regex, insertion: String): String? {
        val matches = anchor.findAll(source).toList()
        if (matches.size != 1) return null
        val at = matches.single().range.last + 1
        return source.substring(0, at) + insertion + source.substring(at)
    }
}
