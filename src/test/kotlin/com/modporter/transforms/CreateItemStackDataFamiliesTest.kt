package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateItemStackDataFamilies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateItemStackDataFamiliesTest {
    @Test
    fun `lazy list filter admission rejects every external or observable pre-test access`() {
        val filter = """
            package com.simibubi.create.content.logistics.filter;
            public class FilterItemStack {
                public static class ListFilterItemStack {
                    public List<FilterItemStack> containedItems;
                    void initialize() {
                        containedItems = new ArrayList<>();
                        containedItems.add(value);
                        for (FilterItemStack entry : containedItems) use(entry);
                    }
                }
            }
        """.trimIndent()
        val onlyOwner = mapOf(
            "src/main/java/com/simibubi/create/content/logistics/filter/FilterItemStack.java" to filter
        )
        assertTrue(CreateItemStackDataFamilies.hasLazyFilterCallerProof(onlyOwner))

        val external = onlyOwner + (
            "src/main/java/example/Observer.java" to
                "package example; public class Observer { void f(ListFilterItemStack s) { use(s.containedItems); } }"
            )
        assertFalse(CreateItemStackDataFamilies.hasLazyFilterCallerProof(external))

        val ownerEscape = filter.replace(
            "void initialize() {",
            "List<FilterItemStack> expose() { return containedItems; }\nvoid initialize() {"
        )
        assertFalse(
            CreateItemStackDataFamilies.hasLazyFilterCallerProof(
                onlyOwner.mapValues { ownerEscape }
            )
        )
    }

    @Test
    fun `filter provider overload emits valid Java instead of a regex group token`() {
        val source = """
            package com.simibubi.create.content.logistics.filter;
            public abstract class FilterItem {
            	public static boolean doPackagesHaveSameData(@NotNull ItemStack a, @NotNull ItemStack b) {
            		return areCapsCompatible(a, b);
            	}

            	public abstract FilterItemStack makeStackWrapper(ItemStack filter);
            }
        """.trimIndent()

        val migrated = CreateItemStackDataFamilies.migrate(source, lazyFilterCallerProof = true)
        val overloadAndDeclaration =
            "\tpublic FilterItemStack makeStackWrapper(ItemStack filter, HolderLookup.Provider registries) {\n" +
                "\t\treturn makeStackWrapper(filter);\n" +
                "\t}\n\n" +
                "\tpublic abstract FilterItemStack makeStackWrapper(ItemStack filter);"

        assertTrue(migrated.contains(overloadAndDeclaration), migrated)
        assertTrue(migrated.contains("import net.minecraft.nbt.CompoundTag;"), migrated)
        assertFalse(migrated.contains("\$1"), migrated)
        assertEquals(migrated.count { it == '{' }, migrated.count { it == '}' }, migrated)
        assertEquals(
            migrated,
            CreateItemStackDataFamilies.migrate(migrated, lazyFilterCallerProof = true)
        )
    }

    @Test
    fun `filter bridge snapshots construction data before mutation and resolves once`() {
        val source = """
            package com.simibubi.create.content.logistics.filter;
            public class FilterItemStack {
            	public static FilterItemStack of(ItemStack filter) {
            		if (filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) && filter.getItem() instanceof FilterItem item) {
            			trimFilterTag(filter);
            			return item.makeStackWrapper(filter);
            		}
            		return new FilterItemStack(filter);
            	}
            	public static FilterItemStack of(CompoundTag tag, HolderLookup.Provider registries) {
            		return of(ItemStack.parseOptional(registries, tag));
            	}
            	public ItemStack item() { return filterItemStack; }
            	public static class ListFilterItemStack extends FilterItemStack {
            		public List<FilterItemStack> containedItems;
            		public boolean shouldRespectNBT;
            		public boolean isBlacklist;
            		public ListFilterItemStack(ItemStack filter, HolderLookup.Provider registries) {
            			super(filter);
            			boolean defaults = !filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            			containedItems = new ArrayList<>();
            			ItemStackHandler items = ((ListFilterItem) filter.getItem()).getFilterItemHandler(filter, registries);
            			for (int i = 0; i < items.getSlots(); i++) {
            				ItemStack stackInSlot = items.getStackInSlot(i);
            				if (!stackInSlot.isEmpty())
            					containedItems.add(FilterItemStack.of(stackInSlot));
            			}
            			shouldRespectNBT = defaults ? false
            				: filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
            					.getBoolean("RespectNBT");
            			isBlacklist = defaults ? false
            				: filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
            					.getBoolean("Blacklist");
            		}
            		@Override
            		public boolean test(Level world, ItemStack stack, boolean matchNBT) {
            			for (FilterItemStack filterItemStack : containedItems)
            				if (filterItemStack.test(world, stack, shouldRespectNBT))
            					return !isBlacklist;
            			return isBlacklist;
            		}
            		@Override
            		public boolean test(Level world, FluidStack stack, boolean matchNBT) {
            			for (FilterItemStack filterItemStack : containedItems)
            				if (filterItemStack.test(world, stack, shouldRespectNBT))
            					return !isBlacklist;
            			return isBlacklist;
            		}
            	}
            }
        """.trimIndent()

        val migrated = CreateItemStackDataFamilies.migrate(source, lazyFilterCallerProof = true)
        assertTrue(migrated.contains("private final ItemStack filterSnapshot;"), migrated)
        assertTrue(migrated.contains("super(filter);\n\t\t\tfilterSnapshot = filter.copy();"), migrated)
        assertTrue(migrated.contains("private boolean containedItemsResolved;"), migrated)
        assertTrue(migrated.contains("if (containedItemsResolved)\n\t\t\t\treturn;"), migrated)
        assertTrue(migrated.contains("containedItemsResolved = true;"), migrated)
        assertTrue(
            migrated.indexOf("if (containedItemsResolved)") <
                migrated.indexOf("getFilterItemHandler(filterSnapshot, registries)"),
            migrated
        )
        assertEquals(
            1,
            Regex.escape("getFilterItemHandler(filterSnapshot, registries)")
                .toRegex()
                .findAll(migrated)
                .count()
        )
        assertTrue(
            migrated.contains(
                "public ListFilterItemStack(ItemStack filter, HolderLookup.Provider registries)"
            ) && migrated.contains("resolveContainedItems(registries);"),
            migrated
        )
        assertTrue(migrated.contains("resolveContainedItems(world.registryAccess());"), migrated)
        assertTrue(
            migrated.contains("containedItems.add(FilterItemStack.of(stackInSlot, registries));"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "!filterSnapshot.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)"
            ),
            migrated
        )
        val resolver = migrated.substring(
            migrated.indexOf("private void resolveContainedItems"),
            migrated.indexOf("@Override\n\t\tpublic boolean test")
        )
        assertTrue(resolver.contains("filterSnapshot.getItem()"), resolver)
        assertFalse(resolver.contains("item()"), resolver)
        assertTrue(
            migrated.contains("return of(ItemStack.parseOptional(registries, tag), registries);"),
            migrated
        )
        assertFalse(migrated.contains("BuiltInRegistries"), migrated)
        assertFalse(migrated.contains("RegistryAccess.EMPTY"), migrated)
        assertFalse(migrated.contains("resolveContainedItems(null)"), migrated)
        assertEquals(
            migrated,
            CreateItemStackDataFamilies.migrate(migrated, lazyFilterCallerProof = true)
        )
    }

    @Test
    fun `filter menus commit owned keys and preserve every unrelated custom data sibling`() {
        val listMenu = """
            package com.simibubi.create.content.logistics.filter;
            public class FilterMenu {
            	protected ItemStackHandler createGhostInventory() {
            		return AllItems.FILTER.get().getFilterItemHandler(contentHolder);
            	}
            	@Override
            	protected void saveData(ItemStack filterItem) {
            		super.saveData(filterItem);
            		CompoundTag tag = filterItem.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		tag.putBoolean("RespectNBT", respectNBT);
            		tag.putBoolean("Blacklist", blacklist);
            		if (respectNBT || blacklist)
            			return;
            		for (int i = 0; i < ghostInventory.getSlots(); i++)
            			if (!ghostInventory.getStackInSlot(i).isEmpty())
            				return;
            		filterItem.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(null));
            	}
            }
        """.trimIndent()
        val migratedList = CreateItemStackDataFamilies.migrate(listMenu, true)
        assertTrue(
            migratedList.contains("getFilterItemHandler(contentHolder, player.registryAccess())"),
            migratedList
        )
        assertTrue(migratedList.contains("""tag.remove("Items");"""), migratedList)
        assertTrue(migratedList.contains("""tag.remove("RespectNBT");"""), migratedList)
        assertTrue(migratedList.contains("""tag.remove("Blacklist");"""), migratedList)
        assertTrue(
            migratedList.contains("CustomData.set(DataComponents.CUSTOM_DATA, filterItem, tag);"),
            migratedList
        )
        assertFalse(migratedList.contains("CustomData.of(null)"), migratedList)
        assertFalse(migratedList.contains("tag.clear()"), migratedList)

        val attributeMenu = """
            package com.simibubi.create.content.logistics.filter;
            public class AttributeFilterMenu {
            	@Override
            	protected void saveData(ItemStack filterItem) {
            		{
            			net.minecraft.nbt.CompoundTag modporterCustomData2 = filterItem.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            			modporterCustomData2.putInt("WhitelistMode", whitelistMode.ordinal());
            			filterItem.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(modporterCustomData2));
            		}
            		ListTag attributes = new ListTag();
            		selectedAttributes.forEach(at -> {
            			if (at == null)
            				return;
            			CompoundTag compoundNBT = ItemAttribute.saveStatic(at.getFirst());
            			compoundNBT.putBoolean("Inverted", at.getSecond());
            			attributes.add(compoundNBT);
            		});
            		{
            			net.minecraft.nbt.CompoundTag modporterCustomData = filterItem.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            			modporterCustomData.put("MatchedAttributes", attributes);
            			filterItem.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(modporterCustomData));
            		}
            		if (attributes.isEmpty() && whitelistMode == WhitelistMode.WHITELIST_DISJ)
            			filterItem.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(null));
            	}
            }
        """.trimIndent()
        val migratedAttribute = CreateItemStackDataFamilies.migrate(attributeMenu, true)
        assertTrue(migratedAttribute.contains("""tag.remove("WhitelistMode");"""), migratedAttribute)
        assertTrue(migratedAttribute.contains("""tag.remove("MatchedAttributes");"""), migratedAttribute)
        assertFalse(migratedAttribute.contains("CustomData.of(null)"), migratedAttribute)
        assertFalse(migratedAttribute.contains("tag.clear()"), migratedAttribute)
    }

    @Test
    fun `package native container round trips more than nine slots and legacy saves remain readable`() {
        val source = """
            package com.simibubi.create.content.logistics.box;
            public class PackageItem {
            	public static ItemStack containing(List<ItemStack> stacks) {
            		ItemStackHandler newInv = new ItemStackHandler(9);
            		stacks.forEach(s -> ItemHandlerHelper.insertItemStacked(newInv, s, false));
            		return containing(newInv);
            	}
            	public static ItemStack containing(ItemStackHandler stacks, HolderLookup.Provider registries) {
            		ItemStack box = PackageStyles.getRandomBox();
            		CompoundTag compound = new CompoundTag();
            		compound.put("Items", stacks.serializeNBT(registries));
            		box.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(compound));
            		return box;
            	}
            	public static ItemStackHandler getContents(ItemStack box, HolderLookup.Provider registries) {
            		ItemStackHandler newInv = new ItemStackHandler(9);
            		CompoundTag invNBT = box.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound("Items");
            		if (!invNBT.isEmpty())
            			newInv.deserializeNBT(registries, invNBT);
            		return newInv;
            	}
            	void tooltip(ItemStack pStack, Item.TooltipContext pLevel) {
            		CompoundTag tag = pStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            		if (!tag.contains("Items", Tag.TAG_COMPOUND))
            			return;
            		ItemStackHandler contents = getContents(pStack, pLevel.registries());
            	}
            }
        """.trimIndent()
        val migrated = CreateItemStackDataFamilies.migrate(source, true)
        assertTrue(migrated.contains("box.set(DataComponents.CONTAINER"), migrated)
        assertTrue(migrated.contains("ItemContainerContents.fromItems(contents)"), migrated)
        assertTrue(migrated.contains("slot < stacks.getSlots()"), migrated)
        assertTrue(migrated.contains("if (box.has(DataComponents.CONTAINER))"), migrated)
        assertTrue(
            migrated.contains("new ItemStackHandler(Math.max(9, contents.getSlots()))"),
            migrated
        )
        assertTrue(migrated.contains("slot < contents.getSlots()"), migrated)
        assertFalse(migrated.contains("Math.min("), migrated)
        assertTrue(migrated.contains("newInv.deserializeNBT(registries, invNBT);"), migrated)
        assertTrue(
            migrated.contains(
                """if (!pStack.has(DataComponents.CONTAINER) && !tag.contains("Items", Tag.TAG_COMPOUND))"""
            ),
            migrated
        )
        assertFalse(migrated.contains("containing(newInv, null)"), migrated)
    }

    @Test
    fun `clipboard entry provider and packet paths preserve components and optional empty identity`() {
        val entry = """
            package com.simibubi.create.content.equipment.clipboard;
            public class ClipboardEntry {
            	public static void saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem) {
            		CompoundTag tag = clipboardItem.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		tag.put("Pages", NBTHelper.writeCompoundList(entries, list -> {
            			CompoundTag pageTag = new CompoundTag();
            			pageTag.put("Entries", NBTHelper.writeCompoundList(list, ClipboardEntry::writeNBT));
            			return pageTag;
            		}));
            	}
            	public CompoundTag writeNBT() {
            		CompoundTag nbt = new CompoundTag();
            		nbt.putBoolean("Checked", checked);
            		nbt.putString("Text", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, text).getOrThrow().toString());
            		if (icon.isEmpty())
            			return nbt;
            		nbt.put("Icon", icon.serializeNBT());
            		nbt.putInt("ItemAmount", itemAmount);
            		return nbt;
            	}
            }
        """.trimIndent()
        val migratedEntry = CreateItemStackDataFamilies.migrate(entry, true)
        assertTrue(
            migratedEntry.contains(
                "saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem, HolderLookup.Provider registries)"
            ),
            migratedEntry
        )
        assertTrue(
            migratedEntry.contains("entry -> entry.writeNBT(registries)"),
            migratedEntry
        )
        assertTrue(migratedEntry.contains("Component.Serializer.toJson(text, registries)"), migratedEntry)
        assertTrue(migratedEntry.contains("icon.saveOptional(registries)"), migratedEntry)
        assertTrue(
            migratedEntry.contains("CustomData.set(\n\t\t\tDataComponents.CUSTOM_DATA, clipboardItem, tag);"),
            migratedEntry
        )

        val packet = """
            package com.simibubi.create.content.equipment.clipboard;
            public class ClipboardEditPacket {
            	void apply(ClipboardBlockEntity cbe, ItemStack itemStack, CompoundTag data) {
            		data = CreateNBTProcessors.clipboardProcessor(data);
            		cbe.dataContainer.setTag(data.isEmpty() ? null : data);
            		itemStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data.isEmpty() ? null : data));
            	}
            }
        """.trimIndent()
        val migratedPacket = CreateItemStackDataFamilies.migrate(packet, true)
        assertTrue(
            migratedPacket.contains("CustomData.set(DataComponents.CUSTOM_DATA, cbe.dataContainer, data);"),
            migratedPacket
        )
        assertTrue(
            migratedPacket.contains("CustomData.set(DataComponents.CUSTOM_DATA, itemStack, data);"),
            migratedPacket
        )
        assertTrue(
            migratedPacket.contains("if (data == null)\n\t\t\t\tdata = new CompoundTag();"),
            migratedPacket
        )
        assertFalse(migratedPacket.contains("CustomData.of(null)"), migratedPacket)
    }

    @Test
    fun `clipboard arity transaction includes package port and rejects an unknown caller without writes`() {
        val entry = """
            package com.simibubi.create.content.equipment.clipboard;
            public class ClipboardEntry {
            	public static void saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem) {
            		CompoundTag tag = clipboardItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            		tag.put("Pages", NBTHelper.writeCompoundList(entries, list -> {
            			CompoundTag pageTag = new CompoundTag();
            			pageTag.put("Entries", NBTHelper.writeCompoundList(list, ClipboardEntry::writeNBT));
            			return pageTag;
            		}));
            	}
            	public CompoundTag writeNBT() {
            		CompoundTag nbt = new CompoundTag();
            		nbt.putString("Text", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, text).getOrThrow().toString());
            		if (icon.isEmpty())
            			return nbt;
            		nbt.put("Icon", icon.serializeNBT());
            		return nbt;
            	}
            }
        """.trimIndent()
        val packagePort = """
            package com.simibubi.create.content.logistics.packagePort;
            public abstract class PackagePortBlockEntity {
            	void addAddressToClipboard(Player player, ItemStack mainHandItem) {
            		ClipboardEntry.saveAll(list, mainHandItem);
            	}
            }
        """.trimIndent()
        val entryPath =
            "src/main/java/com/simibubi/create/content/equipment/clipboard/ClipboardEntry.java"
        val portPath =
            "src/main/java/com/simibubi/create/content/logistics/packagePort/PackagePortBlockEntity.java"
        val admitted = linkedMapOf(entryPath to entry, portPath to packagePort)

        val migrated = CreateItemStackDataFamilies.migrateProject(admitted)
        assertTrue(migrated !== admitted)
        assertTrue(
            migrated.getValue(portPath).contains(
                "ClipboardEntry.saveAll(list, mainHandItem, player.registryAccess())"
            ),
            migrated.getValue(portPath)
        )
        assertTrue(
            migrated.getValue(entryPath).contains(
                "saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem, " +
                    "HolderLookup.Provider registries)"
            ),
            migrated.getValue(entryPath)
        )

        val unknownPath = "src/main/java/example/UnknownClipboardWriter.java"
        val unknown = admitted + (
            unknownPath to
                """
                    package example;
                    public class UnknownClipboardWriter {
                    	void save(ItemStack stack) {
                    		ClipboardEntry.saveAll(pages, stack);
                    	}
                    }
                """.trimIndent()
            )
        val rejected = CreateItemStackDataFamilies.migrateProject(unknown)
        assertTrue(rejected === unknown)
        assertEquals(entry, rejected.getValue(entryPath))
        assertEquals(packagePort, rejected.getValue(portPath))
    }

    @Test
    fun `clipboard screen and interaction use exact target APIs and provider timing`() {
        val screen = """
            package com.simibubi.create.content.equipment.clipboard;
            import net.minecraft.SharedConstants;
            public class ClipboardScreen {
            	private void send() {
            		ClipboardEntry.saveAll(pages, item);
            		ClipboardOverrides.switchTo(ClipboardType.WRITTEN, item);
            		if (pages.isEmpty())
            			item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(new CompoundTag()));
            	}
            	public boolean charTyped(char pCodePoint, int pModifiers) {
            		if (!SharedConstants.isAllowedChatCharacter(pCodePoint))
            			return false;
            		return true;
            	}
            }
        """.trimIndent()
        val migratedScreen = CreateItemStackDataFamilies.migrate(screen, true)
        assertTrue(
            migratedScreen.contains(
                "ClipboardEntry.saveAll(pages, item, minecraft.level.registryAccess())"
            ),
            migratedScreen
        )
        assertTrue(migratedScreen.contains("StringUtil.isAllowedChatCharacter(pCodePoint)"), migratedScreen)
        assertTrue(migratedScreen.contains("""tag.remove("Pages");"""), migratedScreen)
        assertTrue(migratedScreen.contains("""tag.remove("Type");"""), migratedScreen)
        assertTrue(migratedScreen.contains("""tag.remove("Readonly");"""), migratedScreen)
        assertFalse(migratedScreen.contains("CustomData.of(new CompoundTag())"), migratedScreen)

        val handler = """
            package com.simibubi.create.content.equipment.clipboard;
            public class ClipboardValueSettingsHandler {
            	void hover(Minecraft mc) {
            		CompoundTag tagElement = mc.player.getMainHandItem()
            			.getTagElement("CopiedValues");
            	}
            	void first(PlayerInteractEvent event, ItemStack itemStack) {
            		event.setCanceled(true);
            		switch (event) {
            			case PlayerInteractEvent.EntityInteractSpecific e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.EntityInteract e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.RightClickBlock e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.RightClickItem e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			default -> {}
            		}
            		ClipboardEntry.saveAll(listTo, itemStack);
            	}
            	void second(PlayerInteractEvent event) {
            		event.setCanceled(true);
            		switch (event) {
            			case PlayerInteractEvent.EntityInteractSpecific e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.EntityInteract e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.RightClickBlock e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			case PlayerInteractEvent.RightClickItem e -> e.setCancellationResult(InteractionResult.SUCCESS);
            			default -> {}
            		}
            	}
            	private static CompoundTag getCustomDataChild(ItemStack stack, String key) { return null; }
            }
        """.trimIndent()
        val migratedHandler = CreateItemStackDataFamilies.migrate(handler, true)
        assertTrue(
            migratedHandler.contains(
                """getCustomDataChild(mc.player.getMainHandItem(), "CopiedValues")"""
            ),
            migratedHandler
        )
        assertTrue(
            migratedHandler.contains(
                "ClipboardEntry.saveAll(listTo, itemStack, event.getEntity().registryAccess())"
            ),
            migratedHandler
        )
        assertEquals(
            2,
            Regex.escape("cancelInteraction(event);").toRegex().findAll(migratedHandler).count()
        )
        assertTrue(migratedHandler.contains("rightClick.setCanceled(true);"), migratedHandler)
        assertTrue(migratedHandler.contains("leftClick.setCanceled(true);"), migratedHandler)
        assertFalse(migratedHandler.contains("event.setCanceled(true)"), migratedHandler)
    }

    @Test
    fun `material checklist writes native book content and commits clipboard after page and type writes`() {
        val source = """
            package com.simibubi.create.content.schematics.cannon;
            public class MaterialChecklist {
            	public ItemStack createWrittenBook(HolderLookup.Provider registries) {
            		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
            		CompoundTag tag = book.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		ListTag pages = new ListTag();
            		MutableComponent textComponent = Component.empty();
            		pages.add(StringTag.valueOf(net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString()));
            		pages.add(StringTag.valueOf(net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString()));
            		pages.add(StringTag.valueOf(net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString()));
            		pages.add(StringTag.valueOf(net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString()));
            		tag.put("pages", pages);
            		tag.putBoolean("readonly", true);
            		tag.putString("author", "Schematicannon");
            		tag.putString("title", ChatFormatting.BLUE + "Material Checklist");
            		textComponent = CreateLang.translateDirect("materialChecklist")
            			.setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withItalic(Boolean.FALSE));
            		book.getOrCreateTagElement("display")
            			.putString("Name", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, textComponent).getOrThrow().toString());
            		book.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            		return book;
            	}
            	public ItemStack createWrittenClipboard(HolderLookup.Provider registries) {
            		ItemStack clipboard = AllBlocks.CLIPBOARD.asStack();
            		CompoundTag tag = clipboard.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		List<List<ClipboardEntry>> pages = new ArrayList<>();
            		ClipboardEntry.saveAll(pages, clipboard);
            		ClipboardOverrides.switchTo(ClipboardType.WRITTEN, clipboard);
            		clipboard.getOrCreateTagElement("display")
            			.putString("Name", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, CreateLang.translateDirect("materialChecklist")
            				.setStyle(Style.EMPTY.withItalic(Boolean.FALSE))).getOrThrow().toString());
            		tag.putBoolean("Readonly", true);
            		clipboard.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            		return clipboard;
            	}
            }
        """.trimIndent()
        val migrated = CreateItemStackDataFamilies.migrate(source, true)
        assertTrue(migrated.contains("List<Filterable<Component>> pages"), migrated)
        assertTrue(migrated.contains("new WrittenBookContent("), migrated)
        assertTrue(migrated.contains("DataComponents.WRITTEN_BOOK_CONTENT"), migrated)
        assertTrue(migrated.contains("book.set(DataComponents.CUSTOM_NAME, textComponent);"), migrated)
        assertTrue(migrated.contains("ClipboardEntry.saveAll(pages, clipboard, registries);"), migrated)
        assertTrue(migrated.contains("clipboard.set(DataComponents.CUSTOM_NAME"), migrated)
        assertTrue(
            migrated.indexOf("ClipboardOverrides.switchTo") <
                migrated.indexOf("CustomData.update(\n\t\t\tDataComponents.CUSTOM_DATA, clipboard"),
            migrated
        )
        assertFalse(migrated.contains("getOrCreateTagElement"), migrated)
        assertFalse(migrated.contains("""tag.put("pages", pages)"""), migrated)
    }

    @Test
    fun `sandpaper and logistical link mutate only owned data with native target bindings`() {
        val sandpaper = """
            package com.simibubi.create.content.equipment.sandPaper;
            public class SandPaperItem {
            	ItemStack finish(ItemStack stack, Level level, LivingEntity entityLiving) {
            		CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		if (tag.contains("Polishing")) {
            			if (level.isClientSide)
            				return stack;
            			tag.remove("Polishing");
            			stack.hurtAndBreak(1, entityLiving, net.minecraft.world.entity.LivingEntity.getSlotForHand(p.getUsedItemHand()));
            		}
            		return stack;
            	}
            	@Override
            	public void releaseUsing(ItemStack stack, Level worldIn, LivingEntity entityLiving, int timeLeft) {
            		CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		if (tag.contains("Polishing")) {
            			ItemStack toPolish = ItemStack.parseOptional(worldIn.registryAccess(), tag.getCompound("Polishing"));
            			player.getInventory().placeItemBackInInventory(toPolish);
            			tag.remove("Polishing");
            		}
            		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            	}
            	@Override
            	void useOn(ItemStack stack, Player player, UseOnContext context) {
            		stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(p.getUsedItemHand()));
            	}
            	void effects(ItemStack stack) {
            		CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            		// After 6 ticks
            	}
            }
        """.trimIndent()
        val migratedSandpaper = CreateItemStackDataFamilies.migrate(sandpaper, true)
        assertTrue(
            migratedSandpaper.contains("""root -> root.remove("Polishing")"""),
            migratedSandpaper
        )
        assertTrue(migratedSandpaper.contains("entityLiving.getUsedItemHand()"), migratedSandpaper)
        assertTrue(migratedSandpaper.contains("context.getHand()"), migratedSandpaper)
        assertFalse(migratedSandpaper.contains("p.getUsedItemHand()"), migratedSandpaper)
        assertFalse(
            migratedSandpaper.contains("stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA"),
            migratedSandpaper
        )

        val linked = """
            package com.simibubi.create.content.logistics.packagerLink;
            public class LogisticallyLinkedBlockItem {
            	public static boolean isTuned(ItemStack pStack) {
            		return pStack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) && pStack.has(DataComponents.BLOCK_ENTITY_DATA);
            	}
            	public static void assignFrequency(ItemStack stack, Player player, UUID frequency) {
            		CompoundTag stackTag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		CompoundTag teTag = new CompoundTag();
            		teTag.putUUID("Freq", frequency);
            		stackTag.put(BLOCK_ENTITY_TAG, teTag);
            		player.displayClientMessage(CreateLang.translateDirect("logistically_linked.tuned"), true);
            		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(stackTag));
            	}
            }
        """.trimIndent()
        val migratedLinked = CreateItemStackDataFamilies.migrate(linked, true)
        assertTrue(
            migratedLinked.contains("return pStack.has(DataComponents.BLOCK_ENTITY_DATA);"),
            migratedLinked
        )
        assertTrue(
            migratedLinked.contains(
                "stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag()"
            ),
            migratedLinked
        )
        assertTrue(migratedLinked.contains("BlockEntity.addEntityType(tag,"), migratedLinked)
        assertTrue(
            migratedLinked.contains("stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));"),
            migratedLinked
        )
        assertFalse(migratedLinked.contains("BLOCK_ENTITY_TAG"), migratedLinked)
    }

    @Test
    fun `blueprint provider arguments and clipboard written state are exact and idempotent`() {
        val blueprint = """
            package com.simibubi.create.content.equipment.blueprint;
            public class BlueprintItem {
            	ItemStack first(ItemStack result, HolderLookup.Provider registries) {
            		ItemStackHandler filterItems = AllItems.FILTER.get().getFilterItemHandler(result);
            		return result;
            	}
            	ItemStack second(ItemStack result, HolderLookup.Provider registries) {
            		ItemStackHandler filterItems = AllItems.FILTER.get().getFilterItemHandler(result);
            		return result;
            	}
            }
        """.trimIndent()
        val migratedBlueprint = CreateItemStackDataFamilies.migrate(blueprint, true)
        assertEquals(
            2,
            "getFilterItemHandler\\(result, registries\\)".toRegex().findAll(migratedBlueprint).count()
        )

        val blockEntity = """
            package com.simibubi.create.content.equipment.clipboard;
            public class ClipboardBlockEntity {
            	void state() {
            		boolean shouldBeWritten = dataContainer.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag() != null;
            	}
            }
        """.trimIndent()
        val migratedBlockEntity = CreateItemStackDataFamilies.migrate(blockEntity, true)
        assertTrue(
            migratedBlockEntity.contains(
                "boolean shouldBeWritten = dataContainer.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);"
            ),
            migratedBlockEntity
        )
        assertEquals(
            migratedBlockEntity,
            CreateItemStackDataFamilies.migrate(migratedBlockEntity, true)
        )
    }
}
