package com.kidnapmod;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * All mod items are vanilla items with a custom_data tag, so players do NOT need the mod installed
 * on their client (server-side mod).
 */
public final class KidnapItems {
	public static final String TAG = "kidnapmod";

	public static final String HANDCUFFS = "handcuffs";
	public static final String LEGCUFFS = "legcuffs";
	public static final String DUCT_TAPE = "ducttape";
	public static final String HEAD_COVER = "headcover";
	public static final String FILE = "file";
	public static final String HAND_KEY = "hand_key";
	public static final String LEG_KEY = "leg_key";

	public static final List<String> GIVEABLE = List.of(HANDCUFFS, LEGCUFFS, DUCT_TAPE, HEAD_COVER, FILE);

	private KidnapItems() {}

	public static ItemStack create(String kind) {
		return switch (kind) {
			case HANDCUFFS -> base(Items.LEAD, kind, "Handcuffs");
			case LEGCUFFS -> base(Items.IRON_BARS, kind, "Leg Cuffs");
			case DUCT_TAPE -> base(Items.GRAY_DYE, kind, "Duct Tape");
			case HEAD_COVER -> base(Items.BLACK_WOOL, kind, "Head Cover");
			case FILE -> base(Items.FLINT, kind, "Filing Tool");
			default -> ItemStack.EMPTY;
		};
	}

	public static ItemStack key(String kind, String keyId, String targetName) {
		boolean hand = kind.equals(HAND_KEY);
		ItemStack s = base(Items.TRIPWIRE_HOOK, kind, hand ? "Handcuff Key" : "Leg Cuff Key");
		CompoundTag t = s.get(DataComponents.CUSTOM_DATA).copyTag();
		t.putString("key", keyId);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		s.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Opens the " + (hand ? "handcuffs" : "leg cuffs") + " on " + targetName).withStyle(ChatFormatting.GRAY),
			Component.literal("Key ID: " + keyId).withStyle(ChatFormatting.DARK_GRAY))));
		return s;
	}

	private static ItemStack base(Item item, String kind, String name) {
		ItemStack s = new ItemStack(item);
		CompoundTag t = new CompoundTag();
		t.putString(TAG, kind);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		s.set(DataComponents.ITEM_NAME, Component.literal(name));
		return s;
	}

	/** Returns the kidnapmod kind of the stack, or "" if it is not one of ours. */
	public static String kindOf(ItemStack s) {
		if (s.isEmpty()) return "";
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getStringOr(TAG, "");
	}

	public static String keyOf(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getStringOr("key", "");
	}
}
