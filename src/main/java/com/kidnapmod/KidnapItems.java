package com.kidnapmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;

/**
 * SERVER-SIDE items: plain vanilla items + custom name + custom_data + custom_model_data.
 * The resource pack retextures them, so vanilla / ViaVersion clients can join without any mod.
 */
public final class KidnapItems {
	public static final String TAG = "kidnapmod";

	public static final String HANDCUFFS = "handcuffs";
	public static final String LEGCUFFS = "legcuffs";
	public static final String ZIP_TIE = "ziptie";
	public static final String DUCT_TAPE = "ducttape";
	public static final String GAG = "gag";
	public static final String MASK = "mask";
	public static final String EARMUFFS = "earmuffs";
	public static final String HEAD_COVER = "headcover";
	public static final String FILE = "file";
	public static final String LOCKPICK = "lockpick";
	public static final String LEASH = "leash";
	public static final String SHACKLE = "shackle";
	public static final String DART = "dart";
	public static final String HAND_KEY = "hand_key";
	public static final String LEG_KEY = "leg_key";

	public static final List<String> TIERS = List.of("iron", "gold", "diamond", "netherite");

	// worn visuals (equipment asset ids, same names in the resource pack)
	public static final String WORN_TAPE = "worn_tape";
	public static final String WORN_GAG = "worn_gag";
	public static final String WORN_MASK = "worn_mask";
	public static final String WORN_EARMUFFS = "worn_earmuffs";
	public static final String WORN_HEAD_COVER = "worn_head_cover";

	public static String wornHands(String tier) { return "worn_handcuffs_" + tier; }
	public static String wornLegs(String tier) { return "worn_legcuffs_" + tier; }

	private record Spec(String base, String name, int cmd) {}

	/** cmd numbers MUST match the resource pack / recipes (tiers add +100 each). */
	private static final Map<String, Spec> SPECS = Map.ofEntries(
		Map.entry(HANDCUFFS, new Spec("lead", "Handcuffs", 1001)),
		Map.entry(LEGCUFFS, new Spec("iron_ingot", "Leg Cuffs", 1002)),
		Map.entry(DUCT_TAPE, new Spec("paper", "Duct Tape", 1003)),
		Map.entry(HEAD_COVER, new Spec("leather", "Head Cover", 1004)),
		Map.entry(FILE, new Spec("flint", "Filing Tool", 1005)),
		Map.entry(LEASH, new Spec("string", "Captive Leash", 1006)),
		Map.entry(HAND_KEY, new Spec("gold_nugget", "Handcuff Key", 1007)),
		Map.entry(LEG_KEY, new Spec("iron_nugget", "Leg Cuff Key", 1008)),
		Map.entry(DART, new Spec("arrow", "Sleeping Dart", 1009)),
		Map.entry(ZIP_TIE, new Spec("slime_ball", "Zip Tie", 1010)),
		Map.entry(GAG, new Spec("bone", "Cloth Gag", 1011)),
		Map.entry(MASK, new Spec("bowl", "Full Mask", 1012)),
		Map.entry(EARMUFFS, new Spec("coal", "Earmuffs", 1013)),
		Map.entry(LOCKPICK, new Spec("stick", "Lockpick", 1014)),
		Map.entry(SHACKLE, new Spec("netherite_scrap", "Shackle Chain", 1015)));

	/** Names usable in /kidnapmod give and shown in the creative tab. */
	public static final List<String> GIVEABLE = buildGiveable();

	private static List<String> buildGiveable() {
		List<String> l = new ArrayList<>();
		for (String t : TIERS) l.add(t.equals("iron") ? HANDCUFFS : HANDCUFFS + "_" + t);
		for (String t : TIERS) l.add(t.equals("iron") ? LEGCUFFS : LEGCUFFS + "_" + t);
		l.addAll(List.of(ZIP_TIE, DUCT_TAPE, GAG, MASK, EARMUFFS, HEAD_COVER, FILE, LOCKPICK, LEASH, SHACKLE, DART));
		return l;
	}

	private KidnapItems() {}

	private static String cap(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static ItemStack build(String baseId, String kind, String name, int cmd, String model) {
		Item base = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(baseId));
		ItemStack s = new ItemStack(base);
		CompoundTag t = new CompoundTag();
		t.putString(TAG, kind);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false)));
		if (cmd > 0) {
			s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of((float) cmd), List.of(), List.of(model), List.of()));
		}
		return s;
	}

	public static ItemStack create(String kind) {
		return create(kind, "iron");
	}

	public static ItemStack create(String kind, String tier) {
		Spec sp = SPECS.get(kind);
		if (sp == null) return ItemStack.EMPTY;
		boolean tiered = kind.equals(HANDCUFFS) || kind.equals(LEGCUFFS);
		int ti = Math.max(0, TIERS.indexOf(tier));
		if (!tiered) return build(sp.base(), kind, sp.name(), sp.cmd(), kind);
		String tn = TIERS.get(ti);
		ItemStack s = build(sp.base(), kind, cap(tn) + " " + sp.name(), sp.cmd() + ti * 100, kind + "_" + tn);
		CompoundTag t = s.get(DataComponents.CUSTOM_DATA).copyTag();
		t.putString("tier", tn);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		return s;
	}

	/** For commands / creative tab: "handcuffs", "handcuffs_gold", "dart", ... */
	public static ItemStack createByName(String name) {
		for (String t : TIERS) {
			if (name.equals(HANDCUFFS + "_" + t)) return create(HANDCUFFS, t);
			if (name.equals(LEGCUFFS + "_" + t)) return create(LEGCUFFS, t);
		}
		ItemStack s = create(name, "iron");
		if (name.equals(DART) || name.equals(ZIP_TIE)) s.setCount(16);
		return s;
	}

	public static String tierOf(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "iron" : d.copyTag().getStringOr("tier", "iron");
	}

	public static ItemStack key(String kind, String keyId, String targetName) {
		Spec sp = SPECS.get(kind);
		boolean hand = kind.equals(HAND_KEY);
		ItemStack s = build(sp.base(), kind, sp.name(), sp.cmd(), kind);
		CompoundTag t = s.get(DataComponents.CUSTOM_DATA).copyTag();
		t.putString("key", keyId);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		s.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Opens the " + (hand ? "handcuffs" : "leg cuffs") + " on " + targetName).withStyle(ChatFormatting.GRAY),
			Component.literal("Key ID: " + keyId).withStyle(ChatFormatting.DARK_GRAY))));
		return s;
	}

	/** Visible restraint pieces: vanilla armor base + our equipment asset from the resource pack. */
	public static ItemStack worn(String id) {
		String base;
		EquipmentSlot slot;
		if (id.startsWith("worn_handcuffs")) { base = "leather_chestplate"; slot = EquipmentSlot.CHEST; }
		else if (id.startsWith("worn_legcuffs")) { base = "leather_boots"; slot = EquipmentSlot.FEET; }
		else if (id.equals(WORN_HEAD_COVER)) { base = "carved_pumpkin"; slot = EquipmentSlot.HEAD; }
		else if (id.startsWith("worn_")) { base = "leather_helmet"; slot = EquipmentSlot.HEAD; }
		else return ItemStack.EMPTY;
		ItemStack s = build(base, id, "Restraint (worn)", 0, id);
		ResourceKey<EquipmentAsset> asset = ResourceKey.create(Registries.EQUIPMENT_ASSET,
			Identifier.fromNamespaceAndPath(KidnapMod.MOD_ID, id));
		s.set(DataComponents.EQUIPPABLE, Equippable.builder(slot).setAsset(asset).build());
		s.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return s;
	}

	public static String kindOf(ItemStack s) {
		if (s.isEmpty()) return "";
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getStringOr(TAG, "");
	}

	public static boolean isWorn(ItemStack s) {
		return kindOf(s).startsWith("worn_");
	}

	public static String keyOf(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getStringOr("key", "");
	}
}
