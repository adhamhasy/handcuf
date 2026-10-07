package com.kidnapmod.client;

import com.kidnapmod.KidnapItems;
import com.kidnapmod.Settings;
import com.kidnapmod.SyncPayload;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** Optional client part: settings sync + creative tab. Vanilla / ViaVersion clients simply don't have it. */
public class KidnapClient implements ClientModInitializer {
	private static final Set<String> SPECIAL = Set.of(KidnapItems.ZIP_TIE, KidnapItems.DART, KidnapItems.SHACKLE,
		KidnapItems.LOCKPICK, KidnapItems.EARMUFFS);

	@Override
	public void onInitializeClient() {
		Settings.CLIENT.load();
		Settings.CLIENT.save();

		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
			String json = payload.json();
			context.client().execute(() -> ClientData.apply(json));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientData.reset());

		if (Settings.CLIENT.bool("tab_enabled")) registerTab();
	}

	private static void registerTab() {
		boolean allTiers = Settings.CLIENT.bool("tab_all_tiers");
		boolean special = Settings.CLIENT.bool("tab_special");
		boolean bulk = Settings.CLIENT.bool("tab_bulk");

		ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
			Identifier.fromNamespaceAndPath("kidnapmod", "tab"));
		CreativeModeTab tab = FabricCreativeModeTab.builder()
			.title(Component.literal("Kidnap Mod"))
			.icon(() -> KidnapItems.create(KidnapItems.HANDCUFFS))
			.displayItems((params, output) -> {
				for (String name : KidnapItems.GIVEABLE) {
					if (!allTiers && (name.endsWith("_gold") || name.endsWith("_diamond") || name.endsWith("_netherite"))) continue;
					if (!special && SPECIAL.contains(name)) continue;
					ItemStack st = KidnapItems.createByName(name);
					if (!bulk) st.setCount(1);
					output.accept(st);
				}
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, tab);
	}
}
