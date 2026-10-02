package com.kidnapmod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: current server settings, this player's preferences and whether they may edit (op). */
public record SyncPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SyncPayload> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KidnapMod.MOD_ID, "sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SyncPayload::json, SyncPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
