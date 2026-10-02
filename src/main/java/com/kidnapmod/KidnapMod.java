package com.kidnapmod;

import com.google.gson.JsonObject;
import com.kidnapmod.KidnapStore.State;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public class KidnapMod implements ModInitializer {
	public static final String MOD_ID = "kidnapmod";

	static final String TAPE_MUFFLE = "mfmhmf";
	static final String CLOTH_MUFFLE = "mmphmm";

	static final Identifier SLOW_ID = Identifier.fromNamespaceAndPath(MOD_ID, "leg_cuff_slow");
	static final Identifier NOJUMP_ID = Identifier.fromNamespaceAndPath(MOD_ID, "leg_cuff_nojump");
	static final Identifier KO_SLOW_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ko_slow");
	static final Identifier KO_JUMP_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ko_jump");

	/** Same permission check the commands use (ops / game masters). */
	private static final Predicate<CommandSourceStack> OP = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private static final Map<String, String> KIND_SETTING = Map.ofEntries(
		Map.entry(KidnapItems.HANDCUFFS, "item_handcuffs"), Map.entry(KidnapItems.LEGCUFFS, "item_legcuffs"),
		Map.entry(KidnapItems.ZIP_TIE, "item_ziptie"), Map.entry(KidnapItems.DUCT_TAPE, "item_gag_tape"),
		Map.entry(KidnapItems.GAG, "item_gag_cloth"), Map.entry(KidnapItems.MASK, "item_mask"),
		Map.entry(KidnapItems.EARMUFFS, "item_earmuffs"), Map.entry(KidnapItems.HEAD_COVER, "item_headcover"),
		Map.entry(KidnapItems.FILE, "item_file"), Map.entry(KidnapItems.LOCKPICK, "item_lockpick"),
		Map.entry(KidnapItems.LEASH, "item_leash"), Map.entry(KidnapItems.SHACKLE, "item_shackle"),
		Map.entry(KidnapItems.DART, "item_dart"));

	private static final Map<String, String> KIND_PREF = Map.of(
		KidnapItems.LEGCUFFS, "allow_legcuffs", KidnapItems.DUCT_TAPE, "allow_gags", KidnapItems.GAG, "allow_gags",
		KidnapItems.MASK, "allow_gags", KidnapItems.EARMUFFS, "allow_earmuffs", KidnapItems.HEAD_COVER, "allow_headcover",
		KidnapItems.LEASH, "allow_leash", KidnapItems.SHACKLE, "allow_shackle");

	public static final KidnapStore STORE = new KidnapStore();
	private static MinecraftServer SERVER;

	private record Anchor(String dim, double x, double y, double z) {}

	private static final Set<UUID> SPAWNING = new HashSet<>();
	private static final List<Entity> PENDING_DISCARD = new ArrayList<>();
	private static final List<Runnable> TASKS = new ArrayList<>();
	private static final Map<String, long[]> FILING = new HashMap<>();
	private static final Map<UUID, UUID> LEASH = new HashMap<>();
	private static final Map<UUID, UUID> CARRY = new HashMap<>();
	private static final Map<UUID, UUID> TRANSPORT = new HashMap<>();
	private static final Map<UUID, Anchor> ANCHOR = new HashMap<>();
	private static final Map<UUID, Anchor> ANCHOR_PENDING = new HashMap<>();
	private static final Map<UUID, Integer> KO = new HashMap<>();
	private static final Map<UUID, Integer> ESCAPE = new HashMap<>();
	private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

	// ---- settings shortcuts ----
	private static boolean on(String k) { return Settings.SERVER.bool(k); }
	private static double num(String k) { return Settings.SERVER.num(k); }
	private static boolean pref(UUID id, String k) { return Settings.prefBool(id, k); }

	@Override
	public void onInitialize() {
		Settings.SERVER.load();
		Settings.SERVER.save();      // writes the file with every default so ops can see all options
		Settings.loadPrefs();

		PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);

		STORE.onChange = id -> { if (SERVER != null) syncVisuals(SERVER, id); };
		ServerLifecycleEvents.SERVER_STARTED.register(server -> { SERVER = server; STORE.load(server); });
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> STORE.save());
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		// ---------- offline body ----------
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.getPlayer();
			KO.remove(p.getUUID());
			spawnBody(server, p);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer pl = handler.getPlayer();
			UUID id = pl.getUUID();
			for (UUID bodyId : STORE.bodiesOf(id)) {
				for (ServerLevel lvl : server.getAllLevels()) {
					Entity e = lvl.getEntity(bodyId);
					if (e != null) PENDING_DISCARD.add(e);
				}
			}
			KidnapStore.BodyPos bp = STORE.bodyPos(id);
			if (bp != null) {
				STORE.clearBodyPos(id);
				TASKS.add(() -> teleportTo(server, id, bp));
			}
			sendSync(pl);
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity ie && KidnapItems.isWorn(ie.getItem())) {
				PENDING_DISCARD.add(entity);
				return;
			}
			UUID owner = bodyOwner(entity);
			if (owner != null && !SPAWNING.contains(owner) && world.getServer().getPlayerList().getPlayer(owner) != null) {
				PENDING_DISCARD.add(entity);
			}
		});

		// ---------- sleeping dart ----------
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (enabled() && entity instanceof ServerPlayer victim) {
				Entity direct = source.getDirectEntity();
				if (direct != null && isArrow(direct) && KidnapItems.DART.equals(KidnapItems.kindOf(arrowStack(direct)))) {
					if (on("item_dart") && pref(victim.getUUID(), "allow_darts") && kidnappable(victim.getUUID())
						&& !(victim.isCreative() && on("immune_creative"))) {
						direct.discard();
						knockOut(victim);
						return false;
					}
				}
			}
			return true;
		});

		// ---------- restrictions ----------
		AttackBlockCallback.EVENT.register((p, lvl, hand, pos, dir) -> blocked(p, "r_block_mining") ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((lvl, p, pos, state, be) -> !blocked(p, "r_block_mining"));
		AttackEntityCallback.EVENT.register((p, lvl, hand, entity, hit) -> blocked(p, "r_block_attack") ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((p, lvl, hand) -> {
			if (!lvl.isClientSide() && p instanceof ServerPlayer sp && trySelfUnlock(sp, p.getItemInHand(hand))) {
				return InteractionResult.SUCCESS;
			}
			return blocked(p, "r_block_item_use") ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((p, lvl, hand, hit) -> {
			ItemStack held = p.getItemInHand(hand);
			String kind = KidnapItems.kindOf(held);
			if (!lvl.isClientSide() && p instanceof ServerPlayer sp) {
				if (trySelfUnlock(sp, held)) return InteractionResult.SUCCESS;
				if (kind.equals(KidnapItems.SHACKLE) && enabled() && on("item_shackle")) {
					Vec3 c = Vec3.atCenterOf(hit.getBlockPos());
					ANCHOR_PENDING.put(sp.getUUID(), new Anchor(dimId(lvl), c.x, c.y, c.z));
					actionBar(sp, "Anchor set. Now right-click a handcuffed captive with the shackle.");
					return InteractionResult.FAIL;
				}
			}
			if (!kind.isEmpty()) return InteractionResult.FAIL;
			if (isRestrained(p) && ((!held.isEmpty() && on("r_block_placing")) || on("r_block_all_block_use"))) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register(this::onUseEntity);

		// ---------- gags ----------
		ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, message) -> {
			if (sender == null || !enabled() || !on("gag_muffle")) return message;
			State s = STORE.get(sender.getUUID());
			if (s == null || s.gag == null) return message;
			if (s.gag.equals("tape")) return Component.literal(muffle(message.getString(), TAPE_MUFFLE));
			if (s.gag.equals("cloth")) return Component.literal(muffle(message.getString(), CLOTH_MUFFLE));
			return message;
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			State s = STORE.get(sender.getUUID());
			if (enabled() && on("mask_blocks_chat") && s != null && "mask".equals(s.gag)) {
				actionBar(sender, "The mask blocks all speech!");
				return false;
			}
			return true;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	// =====================================================================
	// helpers
	// =====================================================================

	private static boolean enabled() { return on("enabled"); }

	private static boolean isCuffed(UUID id) {
		State s = STORE.get(id);
		return enabled() && s != null && s.handKey != null;
	}

	private static boolean isKo(UUID id) {
		Integer end = KO.get(id);
		return enabled() && end != null && SERVER != null && SERVER.getTickCount() < end;
	}

	private static boolean isRestrained(Player p) {
		return p instanceof ServerPlayer && (isCuffed(p.getUUID()) || isKo(p.getUUID()));
	}

	private static boolean blocked(Player p, String setting) {
		return isRestrained(p) && on(setting);
	}

	/** Consent: has this player agreed to be kidnapped? (respects the server's opt-in rule) */
	private static boolean kidnappable(UUID id) {
		Double v = Settings.prefExplicit(id, "allow_kidnap");
		if (on("require_opt_in")) return v != null && v > 0.5;
		return v == null || v > 0.5;
	}

	private static boolean isOp(ServerPlayer p) {
		try {
			return OP.test(p.createCommandSourceStack());
		} catch (Exception e) {
			return false;
		}
	}

	private static UUID bodyOwner(Entity e) { return STORE.bodyOwner(e.getUUID()); }

	private static UUID targetOf(Entity e) {
		if (e instanceof ServerPlayer sp) return sp.getUUID();
		return bodyOwner(e);
	}

	private static String muffle(String text, String pattern) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (char c : text.toCharArray()) sb.append(Character.isLetterOrDigit(c) ? pattern.charAt(i++ % pattern.length()) : c);
		return sb.toString();
	}

	private static String newKeyId() { return UUID.randomUUID().toString().substring(0, 8); }

	private static void give(ServerPlayer p, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!p.getInventory().add(stack)) {
			ServerLevel lvl = (ServerLevel) p.level();
			lvl.addFreshEntity(new ItemEntity(lvl, p.getX(), p.getY() + 0.5, p.getZ(), stack));
		}
	}

	/** Hint / error line (server master switch). */
	private static void actionBar(ServerPlayer p, String msg) {
		if (on("fb_actionbar")) p.sendSystemMessage(Component.literal(msg), true);
	}

	/** Progress bar (also needs the player's own preference). */
	private static void progress(ServerPlayer p, String msg) {
		if (on("fb_actionbar") && pref(p.getUUID(), "show_progress")) p.sendSystemMessage(Component.literal(msg), true);
	}

	/** Chat message about something the player did. */
	private static void tell(ServerPlayer p, String msg) {
		if (pref(p.getUUID(), "notify_actions")) p.sendSystemMessage(Component.literal(msg));
	}

	/** Chat message to the victim, respecting server + player settings. */
	private static void tellVictim(MinecraftServer server, UUID id, String msg) {
		if (!on("fb_notify_victim")) return;
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p != null && pref(id, "notify_restrained")) p.sendSystemMessage(Component.literal(msg));
	}

	private static void broadcast(MinecraftServer server, String msg, Entity at) {
		if (!on("broadcast_kidnaps")) return;
		String full = msg;
		if (on("broadcast_coords") && at != null) {
			full += String.format(Locale.ROOT, " at %d %d %d", (int) at.getX(), (int) at.getY(), (int) at.getZ());
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal(full), false);
	}

	private static String dimId(Level l) {
		String s = l.dimension().toString();
		int i = s.lastIndexOf('/');
		return (i >= 0 ? s.substring(i + 1) : s).replace("]", "").trim();
	}

	private static Entity findEntity(MinecraftServer server, UUID id) {
		for (ServerLevel lvl : server.getAllLevels()) {
			Entity e = lvl.getEntity(id);
			if (e != null) return e;
		}
		return null;
	}

	private static Entity captiveEntity(MinecraftServer server, UUID owner) {
		ServerPlayer p = server.getPlayerList().getPlayer(owner);
		if (p != null) return p;
		for (UUID b : STORE.bodiesOf(owner)) {
			Entity e = findEntity(server, b);
			if (e != null) return e;
		}
		return null;
	}

	private static void mount(Entity rider, Entity vehicle) { rider.startRiding(vehicle, true, false); }

	private static SoundEvent sound(String... ids) {
		for (String id : ids) {
			SoundEvent s = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.withDefaultNamespace(id));
			if (s != null) return s;
		}
		return null;
	}

	private static void sfx(Entity at, Player exclude, float pitch, String... ids) {
		SoundEvent s = sound(ids);
		float vol = (float) num("sfx_volume");
		if (s != null && vol > 0) at.level().playSound(exclude, at.getX(), at.getY(), at.getZ(), s, SoundSource.PLAYERS, vol, pitch);
	}

	private static void lockClick(Entity at, boolean lock) {
		if (on("sfx_lock")) sfx(at, null, 1.4f, lock ? "block.tripwire.click_on" : "block.tripwire.click_off");
	}

	private static boolean isVehicle(Entity e) {
		String p = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return p.contains("boat") || p.contains("raft") || p.contains("minecart")
			|| p.equals("horse") || p.equals("donkey") || p.equals("mule") || p.equals("pig") || p.equals("strider");
	}

	private static boolean isArrow(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().contains("arrow");
	}

	private static ItemStack arrowStack(Entity arrow) {
		for (String m : new String[] {"getPickupItemStackOrigin", "getPickupItem"}) {
			try {
				Object o = arrow.getClass().getMethod(m).invoke(arrow);
				if (o instanceof ItemStack st && !st.isEmpty()) return st;
			} catch (Exception ignored) {
			}
		}
		return ItemStack.EMPTY;
	}

	private static void knockOut(ServerPlayer p) {
		KO.put(p.getUUID(), SERVER.getTickCount() + (int) (num("ko_s") * 20));
		tellVictim(SERVER, p.getUUID(), "You were hit by a sleeping dart!");
	}

	private static double tierMult(String tier) {
		return switch (tier) {
			case "zip" -> num("mult_zip");
			case "gold" -> num("mult_gold");
			case "diamond" -> num("mult_diamond");
			case "netherite" -> num("mult_netherite");
			default -> 1.0;
		};
	}

	private static void touch(State st) {
		if (st.since == 0) st.since = System.currentTimeMillis();
	}

	private static void teleportTo(MinecraftServer server, UUID player, KidnapStore.BodyPos bp) {
		String cmd = String.format(Locale.ROOT, "execute in %s run tp %s %.3f %.3f %.3f", bp.dim, player, bp.x, bp.y, bp.z);
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
		} catch (Exception e) {
			System.err.println("[kidnapmod] teleport failed: " + e);
		}
	}

	// =====================================================================
	// settings sync to clients (Mod Menu screen)
	// =====================================================================

	static void sendSync(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, SyncPayload.TYPE)) return;
		JsonObject root = new JsonObject();
		root.addProperty("edit", isOp(p));
		JsonObject sv = new JsonObject();
		Settings.SERVER.effective().forEach((k, v) -> sv.addProperty(k, v));
		root.add("server", sv);
		JsonObject pf = new JsonObject();
		for (Settings.Def d : Settings.of(Settings.Scope.PREFS)) {
			double v = d.key().equals("allow_kidnap") ? (kidnappable(p.getUUID()) ? 1 : 0) : Settings.pref(p.getUUID(), d.key());
			pf.addProperty(d.key(), v);
		}
		root.add("prefs", pf);
		ServerPlayNetworking.send(p, new SyncPayload(root.toString()));
	}

	private static void syncAll(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sendSync(p);
	}

	// =====================================================================
	// offline body (vanilla Mannequin)
	// =====================================================================

	private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

	private static String ints(UUID id) {
		long m = id.getMostSignificantBits();
		long l = id.getLeastSignificantBits();
		return (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l;
	}

	private static void spawnBody(MinecraftServer server, ServerPlayer p) {
		if (!enabled() || !on("bodies") || p == null || !p.isAlive() || p.isSpectator()) return;
		UUID id = p.getUUID();
		String name = p.getName().getString();
		UUID bodyId = UUID.randomUUID();
		String lastSeen = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT));

		String common = "{UUID:[I;" + ints(bodyId) + "],profile:{name:\"" + esc(name) + "\",id:[I;" + ints(id) + "]}"
			+ ",CustomName:{text:\"" + esc(name) + "\"},CustomNameVisible:" + (on("body_name_visible") ? "1b" : "0b")
			+ ",immovable:1b,Invulnerable:" + (on("body_invulnerable") ? "1b" : "0b") + ",Silent:1b,Rotation:[" + p.getYRot() + "f,0f]";
		String fancy = common
			+ (on("body_sleeping_pose") ? ",pose:\"sleeping\"" : "")
			+ (on("body_last_seen") ? ",description:{text:\"Last seen " + lastSeen + "\",color:\"gray\"}" : ",hide_description:1b")
			+ "}";
		String plain = common + ",hide_description:1b}";

		SPAWNING.add(id);
		STORE.addBody(bodyId, id);
		try {
			ServerLevel lvl = (ServerLevel) p.level();
			CommandSourceStack src = server.createCommandSourceStack().withLevel(lvl).withSuppressedOutput();
			String at = String.format(Locale.ROOT, "summon minecraft:mannequin %.4f %.4f %.4f ", p.getX(), p.getY(), p.getZ());
			server.getCommands().performPrefixedCommand(src, at + fancy);
			if (lvl.getEntity(bodyId) == null) server.getCommands().performPrefixedCommand(src, at + plain);
			if (lvl.getEntity(bodyId) == null) System.err.println("[kidnapmod] could not spawn body for " + name);
		} catch (Exception e) {
			System.err.println("[kidnapmod] Failed to spawn body for " + name + ": " + e);
		} finally {
			SPAWNING.remove(id);
		}
		syncVisuals(server, id);
	}

	// =====================================================================
	// per tick
	// =====================================================================

	private void onServerTick(MinecraftServer server) {
		if (!PENDING_DISCARD.isEmpty()) {
			List<Entity> copy = new ArrayList<>(PENDING_DISCARD);
			PENDING_DISCARD.clear();
			for (Entity e : copy) {
				e.discard();
				STORE.removeBody(e.getUUID());
			}
		}
		if (!TASKS.isEmpty()) {
			List<Runnable> copy = new ArrayList<>(TASKS);
			TASKS.clear();
			for (Runnable r : copy) r.run();
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) tickPlayer(server, p);
		if (enabled()) tickBindings(server);
	}

	private static String statusText(State s, boolean ko, UUID id) {
		List<String> l = new ArrayList<>();
		if (ko) l.add("asleep");
		if (s != null) {
			if (s.handKey != null) l.add("ZIP".equals(s.handKey) ? "zip-tied" : (s.handTier == null ? "iron" : s.handTier) + " handcuffs");
			if (s.legKey != null) l.add((s.legTier == null ? "iron" : s.legTier) + " leg cuffs");
			if (s.gag != null) l.add(s.gag.equals("tape") ? "taped" : (s.gag.equals("cloth") ? "gagged" : "masked"));
			if (s.earmuffs) l.add("earmuffs");
			if (s.covered) l.add("hooded");
		}
		if (LEASH.containsKey(id)) l.add("leashed");
		if (CARRY.containsKey(id)) l.add("carried");
		if (ANCHOR.containsKey(id)) l.add("shackled");
		return String.join("  |  ", l);
	}

	private void tickPlayer(MinecraftServer server, ServerPlayer p) {
		UUID id = p.getUUID();
		boolean active = enabled();
		State s = active ? STORE.get(id) : null;
		boolean cuffed = s != null && s.handKey != null;
		boolean legs = s != null && s.legKey != null;
		boolean covered = s != null && s.covered;
		boolean ko = isKo(id);
		if (!ko) KO.remove(id);

		// leg cuffs speed from the "seconds per block" setting (normal walking = 4.317 blocks/s)
		double legMod = 1.0 / (Math.max(0.5, num("leg_seconds_per_block")) * 4.317) - 1.0;
		setModifier(p, Attributes.MOVEMENT_SPEED, SLOW_ID, legMod, legs);
		setModifier(p, Attributes.JUMP_STRENGTH, NOJUMP_ID, -1.0, legs && on("leg_block_jump"));
		setModifier(p, Attributes.MOVEMENT_SPEED, KO_SLOW_ID, -1.0, ko);
		setModifier(p, Attributes.JUMP_STRENGTH, KO_JUMP_ID, -1.0, ko);

		if (p.tickCount % 10 == 0) {
			manageEffect(p, MobEffects.MINING_FATIGUE, 9, (cuffed && on("r_mining_fatigue")) || ko);
			manageEffect(p, MobEffects.BLINDNESS, 0, (covered && on("cover_blindness")) || ko);
			manageEffect(p, MobEffects.SLOWNESS, 1, active && on("carry_slows") && CARRY.containsValue(id));
		}
		if ((cuffed || ko) && on("r_block_sprint") && p.isSprinting()) p.setSprinting(false);

		if (ko && p.tickCount % 40 == 0) actionBar(p, "Zzz... you are asleep");

		if (s != null || p.tickCount % 20 == 0) {
			applyVisuals(p, s);
			purgeWorn(p);
		}

		// status line (server allows it AND the player wants it)
		if (s != null && p.tickCount % 20 == 0 && on("fb_status_bar") && pref(id, "status_bar") && !ko) {
			String st = statusText(s, false, id);
			if (!st.isEmpty()) p.sendSystemMessage(Component.literal("Restrained: " + st), true);
		}

		// earmuffs
		if (s != null && s.earmuffs && on("earmuffs_mute") && p.tickCount % Math.max(1, (int) num("earmuffs_interval")) == 0) {
			p.connection.send(new ClientboundStopSoundPacket(null, null));
		}

		// chain clink
		if (legs && on("leg_clink") && p.tickCount % 12 == 0) {
			Vec3 last = LAST_POS.get(id);
			if (last != null && last.distanceToSqr(p.position()) > 0.0004) {
				sfx(p, pref(id, "hear_own_clink") ? null : p, 1.0f, "block.chain.step", "block.iron_chain.step", "block.chain.place");
			}
			LAST_POS.put(id, p.position());
		}

		// auto-release
		double mins = num("auto_release_minutes");
		if (mins > 0 && s != null && s.since > 0 && p.tickCount % 100 == 0
			&& System.currentTimeMillis() - s.since > (long) (mins * 60000L)) {
			LEASH.remove(id); ANCHOR.remove(id); TRANSPORT.remove(id);
			if (CARRY.containsKey(id)) stopCarry(server, id);
			STORE.clear(id);
			tell(p, "Your restraints came loose by themselves.");
			broadcast(server, p.getName().getString() + " got free.", p);
			s = null;
		}

		// struggling free: zip ties always (zip_escape_s), iron handcuffs only if struggle_s > 0
		if (s != null && s.handKey != null) {
			boolean zip = "ZIP".equals(s.handKey);
			String tier = s.handTier == null ? "iron" : s.handTier;
			double secs = zip ? num("zip_escape_s") : num("struggle_s") * tierMult(tier);
			if (secs > 0) {
				int need = (int) (secs * 20);
				int v = ESCAPE.getOrDefault(id, 0);
				v = p.isShiftKeyDown() ? v + 1 : Math.max(0, v - 2);
				ESCAPE.put(id, v);
				if (p.isShiftKeyDown() && v > 0 && p.tickCount % 20 == 0) {
					progress(p, "Straining against your " + (zip ? "zip tie" : "handcuffs") + "... " + (v * 100 / need) + "%");
					double radius = num("struggle_alert_radius");
					if (radius > 0 && v % 100 < 20) {
						for (ServerPlayer o : server.getPlayerList().getPlayers()) {
							if (o != p && o.level() == p.level() && o.distanceTo(p) < radius) {
								actionBar(o, "You hear " + p.getName().getString() + " struggling against their restraints!");
							}
						}
					}
				}
				if (v >= need) {
					ESCAPE.remove(id);
					s.handKey = null;
					s.handTier = null;
					STORE.cleanup(id);
					tell(p, "You broke free of your " + (zip ? "zip tie" : "handcuffs") + "!");
					broadcast(server, p.getName().getString() + " broke free!", p);
					sfx(p, null, 1.0f, "entity.item.break");
				}
			} else {
				ESCAPE.remove(id);
			}
		} else {
			ESCAPE.remove(id);
		}
	}

	private static void setModifier(ServerPlayer p, Holder<Attribute> attr, Identifier id, double amount, boolean on) {
		AttributeInstance inst = p.getAttribute(attr);
		if (inst == null) return;
		AttributeModifier cur = inst.getModifier(id);
		if (on) {
			if (cur == null || Math.abs(cur.amount() - amount) > 1e-6) {
				if (cur != null) inst.removeModifier(id);
				inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		} else if (cur != null) {
			inst.removeModifier(id);
		}
	}

	private static void manageEffect(ServerPlayer p, Holder<MobEffect> effect, int amplifier, boolean on) {
		MobEffectInstance cur = p.getEffect(effect);
		if (on) {
			if (cur == null || cur.getDuration() < 60 || cur.getAmplifier() < amplifier) {
				p.addEffect(new MobEffectInstance(effect, 100, amplifier, true, false, false));
			}
		} else if (cur != null && cur.isAmbient() && cur.getAmplifier() == amplifier && cur.getDuration() <= 100) {
			p.removeEffect(effect);
		}
	}

	// ---------------- visible restraints (armor slots) ----------------

	private static void applyVisuals(LivingEntity e, State s) {
		if (!enabled() || !on("visuals")) s = null;
		String hands = null, legs = null, head = null;
		if (s != null) {
			if (s.handKey != null) hands = KidnapItems.wornHands(s.handTier == null ? "iron" : s.handTier);
			if (s.legKey != null) legs = KidnapItems.wornLegs(s.legTier == null ? "iron" : s.legTier);
			if (s.covered) head = KidnapItems.WORN_HEAD_COVER;
			else if ("mask".equals(s.gag)) head = KidnapItems.WORN_MASK;
			else if ("cloth".equals(s.gag)) head = KidnapItems.WORN_GAG;
			else if ("tape".equals(s.gag)) head = KidnapItems.WORN_TAPE;
			else if (s.earmuffs) head = KidnapItems.WORN_EARMUFFS;
		}
		setWorn(e, EquipmentSlot.CHEST, hands);
		setWorn(e, EquipmentSlot.FEET, legs);
		setWorn(e, EquipmentSlot.HEAD, head);
	}

	private static void setWorn(LivingEntity e, EquipmentSlot slot, String wornId) {
		ItemStack cur = e.getItemBySlot(slot);
		if (wornId != null) {
			if (!KidnapItems.kindOf(cur).equals(wornId)) {
				if (!cur.isEmpty() && !KidnapItems.isWorn(cur) && e instanceof ServerPlayer sp) give(sp, cur.copy());
				e.setItemSlot(slot, KidnapItems.worn(wornId));
			}
		} else if (KidnapItems.isWorn(cur)) {
			e.setItemSlot(slot, ItemStack.EMPTY);
		}
	}

	private static void purgeWorn(ServerPlayer p) {
		for (int i = 0; i < 36; i++) {
			if (KidnapItems.isWorn(p.getInventory().getItem(i))) p.getInventory().setItem(i, ItemStack.EMPTY);
		}
		if (KidnapItems.isWorn(p.getItemBySlot(EquipmentSlot.OFFHAND))) p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		if (KidnapItems.isWorn(p.containerMenu.getCarried())) p.containerMenu.setCarried(ItemStack.EMPTY);
		if (p.containerMenu != p.inventoryMenu) {
			for (Slot sl : p.containerMenu.slots) {
				if (KidnapItems.isWorn(sl.getItem())) sl.set(ItemStack.EMPTY);
			}
		}
	}

	private static void syncVisuals(MinecraftServer server, UUID owner) {
		State s = STORE.get(owner);
		ServerPlayer p = server.getPlayerList().getPlayer(owner);
		if (p != null) applyVisuals(p, s);
		for (UUID b : STORE.bodiesOf(owner)) {
			Entity e = findEntity(server, b);
			if (e instanceof LivingEntity le) applyVisuals(le, s);
		}
	}

	// =====================================================================
	// leash / carry / transport / shackle
	// =====================================================================

	private static void pull(ServerPlayer cap, double tx, double tz, double len) {
		double dx = tx - cap.getX();
		double dz = tz - cap.getZ();
		double hd = Math.sqrt(dx * dx + dz * dz);
		if (hd > len) {
			double step = Math.min(hd - len, 0.5);
			cap.connection.teleport(cap.getX() + dx / hd * step, cap.getY(), cap.getZ() + dz / hd * step, cap.getYRot(), cap.getXRot());
		}
	}

	private static void rope(ServerLevel lvl, Vec3 a, Vec3 b) {
		for (int i = 1; i < 10; i++) {
			Vec3 pt = a.lerp(b, i / 10.0);
			lvl.sendParticles(ParticleTypes.CRIT, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
		}
	}

	private static void stopCarry(MinecraftServer server, UUID captive) {
		CARRY.remove(captive);
		Entity e = captiveEntity(server, captive);
		if (e == null) return;
		e.stopRiding();
		if (!(e instanceof ServerPlayer)) {
			STORE.setBodyPos(captive, dimId(e.level()), e.getX(), e.getY(), e.getZ());
			STORE.save();
		}
	}

	private static boolean holdsLeash(ServerPlayer p) {
		return KidnapItems.LEASH.equals(KidnapItems.kindOf(p.getMainHandItem())) || KidnapItems.LEASH.equals(KidnapItems.kindOf(p.getOffhandItem()));
	}

	private static void tickBindings(MinecraftServer server) {
		int now = server.getTickCount();
		double leashLen = num("leash_length");
		double leashBreak = num("leash_break");
		double anchorR = num("anchor_radius");
		boolean parts = on("leash_particles");

		for (Map.Entry<UUID, UUID> en : new ArrayList<>(CARRY.entrySet())) {
			UUID capId = en.getKey();
			ServerPlayer carrier = server.getPlayerList().getPlayer(en.getValue());
			Entity cap = captiveEntity(server, capId);
			if (carrier == null || !carrier.isAlive() || cap == null || !isCuffed(capId) || cap.level() != carrier.level() || !on("feat_carry")) {
				stopCarry(server, capId);
				continue;
			}
			if (cap.getVehicle() != carrier) mount(cap, carrier);
			if (!(cap instanceof ServerPlayer) && now % 10 == 0) {
				STORE.setBodyPos(capId, dimId(carrier.level()), carrier.getX(), carrier.getY(), carrier.getZ());
			}
		}

		for (Map.Entry<UUID, UUID> en : new ArrayList<>(TRANSPORT.entrySet())) {
			UUID capId = en.getKey();
			Entity vehicle = findEntity(server, en.getValue());
			Entity cap = captiveEntity(server, capId);
			if (vehicle == null || cap == null || !LEASH.containsKey(capId) || !isCuffed(capId) || !on("feat_transport")) {
				TRANSPORT.remove(capId);
				if (cap != null) cap.stopRiding();
				continue;
			}
			if (cap.getVehicle() != vehicle) mount(cap, vehicle);
		}

		List<UUID> drop = new ArrayList<>();
		for (Map.Entry<UUID, UUID> en : LEASH.entrySet()) {
			UUID capId = en.getKey();
			ServerPlayer holder = server.getPlayerList().getPlayer(en.getValue());
			if (holder == null || !holder.isAlive() || !isCuffed(capId) || !on("item_leash")) { drop.add(capId); continue; }
			if (on("leash_requires_holding") && !holdsLeash(holder)) {
				drop.add(capId);
				tell(holder, "You let go of the leash.");
				continue;
			}
			if (TRANSPORT.containsKey(capId) || CARRY.containsKey(capId)) continue;
			ServerPlayer cap = server.getPlayerList().getPlayer(capId);
			if (cap == null) continue;
			if (cap.level() != holder.level() || cap.distanceTo(holder) > leashBreak) {
				drop.add(capId);
				tell(holder, "The leash snapped!");
				tellVictim(server, capId, "The leash snapped!");
				continue;
			}
			pull(cap, holder.getX(), holder.getZ(), leashLen);
			if (parts && now % 2 == 0 && cap.level() instanceof ServerLevel lvl) {
				rope(lvl, holder.position().add(0, 1.0, 0), cap.position().add(0, 1.0, 0));
			}
		}
		for (UUID id : drop) LEASH.remove(id);

		for (Map.Entry<UUID, Anchor> en : new ArrayList<>(ANCHOR.entrySet())) {
			UUID capId = en.getKey();
			Anchor a = en.getValue();
			ServerPlayer cap = server.getPlayerList().getPlayer(capId);
			if (!isCuffed(capId) || !on("item_shackle")) { ANCHOR.remove(capId); continue; }
			if (cap == null || !dimId(cap.level()).equals(a.dim())) continue;
			pull(cap, a.x(), a.z(), anchorR);
			if (parts && now % 4 == 0 && cap.level() instanceof ServerLevel lvl) {
				rope(lvl, new Vec3(a.x(), a.y(), a.z()), cap.position().add(0, 0.8, 0));
			}
		}
	}

	// =====================================================================
	// right-click on a body / player
	// =====================================================================

	/** null = allowed, otherwise the reason it is not. */
	private static String denyReason(String kind, ItemStack held, UUID targetId, Entity entity) {
		String key = KIND_SETTING.get(kind);
		if (key != null && !on(key)) return "This item is disabled on this server.";
		if (kind.equals(KidnapItems.HANDCUFFS) || kind.equals(KidnapItems.LEGCUFFS)) {
			String t = KidnapItems.tierOf(held);
			if (!t.equals("iron") && !on("tier_" + t)) return "That cuff tier is disabled on this server.";
		}
		boolean restraining = !(kind.equals(KidnapItems.FILE) || kind.equals(KidnapItems.LOCKPICK)
			|| kind.equals(KidnapItems.HAND_KEY) || kind.equals(KidnapItems.LEG_KEY));
		if (restraining) {
			if (entity instanceof ServerPlayer tp && tp.isCreative() && on("immune_creative")) return "That player is immune (creative mode).";
			if ((kind.equals(KidnapItems.HANDCUFFS) || kind.equals(KidnapItems.ZIP_TIE)) && !kidnappable(targetId)) {
				return "That player hasn't agreed to be kidnapped.";
			}
			String pk = KIND_PREF.get(kind);
			if (pk != null && !pref(targetId, pk)) return "That player has disabled this restraint.";
		}
		return null;
	}

	private InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer actor) || !enabled()) {
			return InteractionResult.PASS;
		}
		MinecraftServer server = actor.level().getServer();
		ItemStack held = actor.getItemInHand(hand);
		String kind = KidnapItems.kindOf(held);
		UUID targetId = targetOf(entity);

		if (isRestrained(actor)) {
			if (!held.isEmpty() || targetId != null) {
				actionBar(actor, "You can't do that right now!");
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		}

		if (kind.equals(KidnapItems.LEASH) && targetId == null && isVehicle(entity)) {
			if (!on("item_leash") || !on("feat_transport")) { actionBar(actor, "Transport is disabled on this server."); return InteractionResult.FAIL; }
			for (Map.Entry<UUID, UUID> en : LEASH.entrySet()) {
				if (!en.getValue().equals(actor.getUUID())) continue;
				Entity cap = captiveEntity(server, en.getKey());
				if (cap != null && cap.distanceTo(entity) < 12) {
					mount(cap, entity);
					TRANSPORT.put(en.getKey(), entity.getUUID());
					tell(actor, "Your captive is now strapped in.");
					return InteractionResult.SUCCESS;
				}
			}
			actionBar(actor, "Leash a handcuffed captive first (and bring them close).");
			return InteractionResult.FAIL;
		}

		if (targetId == null) return kind.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;
		if (targetId.equals(actor.getUUID())) return kind.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;

		if (!kind.isEmpty() && !kind.startsWith("worn_")) {
			String why = denyReason(kind, held, targetId, entity);
			if (why != null) { actionBar(actor, why); return InteractionResult.FAIL; }
		}

		boolean offline = bodyOwner(entity) != null;
		String name = entity.getName().getString();
		State ts = STORE.get(targetId);
		boolean targetCuffed = ts != null && ts.handKey != null;
		boolean helpless = offline || isKo(targetId) || on("allow_online_handcuff");
		boolean accessible = offline || isKo(targetId) || targetCuffed;
		String need = "They must be offline, asleep or handcuffed first.";

		switch (kind) {
			case KidnapItems.HANDCUFFS, KidnapItems.ZIP_TIE -> {
				boolean zip = kind.equals(KidnapItems.ZIP_TIE);
				if (!helpless) { actionBar(actor, "Only OFFLINE or sleeping players can be handcuffed."); return InteractionResult.FAIL; }
				if (targetCuffed) { actionBar(actor, name + " is already restrained."); return InteractionResult.FAIL; }
				State st = STORE.getOrCreate(targetId);
				String keyId = zip ? "ZIP" : newKeyId();
				st.handKey = keyId;
				st.handTier = zip ? "zip" : KidnapItems.tierOf(held);
				touch(st);
				STORE.cleanup(targetId);
				held.shrink(1);
				lockClick(entity, true);
				if (zip) {
					tell(actor, "You zip-tied " + name + ".");
				} else {
					give(actor, KidnapItems.key(KidnapItems.HAND_KEY, keyId, name));
					tell(actor, "You handcuffed " + name + "! You received the handcuff key.");
				}
				broadcast(server, actor.getName().getString() + " kidnapped " + name + "!", entity);
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.LEGCUFFS -> {
				if (!accessible) { actionBar(actor, need); return InteractionResult.FAIL; }
				if (ts != null && ts.legKey != null) { actionBar(actor, name + " already has leg cuffs."); return InteractionResult.FAIL; }
				State st = STORE.getOrCreate(targetId);
				String keyId = newKeyId();
				st.legKey = keyId;
				st.legTier = KidnapItems.tierOf(held);
				touch(st);
				STORE.cleanup(targetId);
				held.shrink(1);
				lockClick(entity, true);
				give(actor, KidnapItems.key(KidnapItems.LEG_KEY, keyId, name));
				tell(actor, "You put leg cuffs on " + name + "! You received the leg cuff key.");
				tellVictim(server, targetId, "Someone locked leg cuffs on you!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.DUCT_TAPE, KidnapItems.GAG, KidnapItems.MASK -> {
				if (!accessible) { actionBar(actor, need); return InteractionResult.FAIL; }
				if (ts != null && ts.gag != null) { actionBar(actor, name + " is already gagged."); return InteractionResult.FAIL; }
				State st = STORE.getOrCreate(targetId);
				st.gag = kind.equals(KidnapItems.DUCT_TAPE) ? "tape" : (kind.equals(KidnapItems.GAG) ? "cloth" : "mask");
				touch(st);
				STORE.cleanup(targetId);
				held.shrink(1);
				tell(actor, "You gagged " + name + ".");
				tellVictim(server, targetId, "Someone gagged you!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.EARMUFFS -> {
				if (!accessible) { actionBar(actor, need); return InteractionResult.FAIL; }
				if (ts != null && ts.earmuffs) { actionBar(actor, name + " already has earmuffs."); return InteractionResult.FAIL; }
				State st = STORE.getOrCreate(targetId);
				st.earmuffs = true;
				touch(st);
				STORE.cleanup(targetId);
				held.shrink(1);
				tell(actor, "You put earmuffs on " + name + ".");
				tellVictim(server, targetId, "Someone put earmuffs on you. Everything goes quiet...");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.HEAD_COVER -> {
				if (!accessible) { actionBar(actor, need); return InteractionResult.FAIL; }
				if (ts != null && ts.covered) { actionBar(actor, name + " already has a head cover."); return InteractionResult.FAIL; }
				State st = STORE.getOrCreate(targetId);
				st.covered = true;
				touch(st);
				STORE.cleanup(targetId);
				held.shrink(1);
				tell(actor, "You put a head cover on " + name + ".");
				tellVictim(server, targetId, "Someone put a bag over your head!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.HAND_KEY, KidnapItems.LEG_KEY -> {
				boolean hk = kind.equals(KidnapItems.HAND_KEY);
				String k = KidnapItems.keyOf(held);
				if (ts != null && hk && ts.handKey != null && ts.handKey.equals(k)) {
					String tier = ts.handTier == null ? "iron" : ts.handTier;
					ts.handKey = null; ts.handTier = null;
					STORE.cleanup(targetId);
					held.shrink(1);
					give(actor, KidnapItems.create(KidnapItems.HANDCUFFS, tier));
					lockClick(entity, false);
					tell(actor, "You unlocked " + name + "'s handcuffs.");
					tellVictim(server, targetId, "Your handcuffs were unlocked.");
					broadcast(server, name + " was freed by " + actor.getName().getString() + ".", entity);
				} else if (ts != null && !hk && ts.legKey != null && ts.legKey.equals(k)) {
					String tier = ts.legTier == null ? "iron" : ts.legTier;
					ts.legKey = null; ts.legTier = null;
					STORE.cleanup(targetId);
					held.shrink(1);
					give(actor, KidnapItems.create(KidnapItems.LEGCUFFS, tier));
					lockClick(entity, false);
					tell(actor, "You unlocked " + name + "'s leg cuffs.");
					tellVictim(server, targetId, "Your leg cuffs were unlocked.");
				} else {
					actionBar(actor, "This key doesn't fit anything on " + name + ".");
				}
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.FILE -> { return workOnCuffs(server, actor, held, entity, targetId, name, false); }
			case KidnapItems.LOCKPICK -> { return workOnCuffs(server, actor, held, entity, targetId, name, true); }
			case KidnapItems.LEASH -> {
				if (!targetCuffed) { actionBar(actor, "Only handcuffed players can be leashed or carried."); return InteractionResult.FAIL; }
				if (actor.isShiftKeyDown()) {
					if (!on("feat_carry")) { actionBar(actor, "Carrying is disabled on this server."); return InteractionResult.FAIL; }
					if (!CARRY.containsKey(targetId) && !pref(targetId, "allow_carry")) { actionBar(actor, "That player doesn't allow being carried."); return InteractionResult.FAIL; }
					if (CARRY.containsKey(targetId)) {
						stopCarry(server, targetId);
						tell(actor, "You put " + name + " down.");
					} else {
						CARRY.put(targetId, actor.getUUID());
						mount(entity, actor);
						tell(actor, "You picked up " + name + ". Sneak + right-click with the leash to put them down.");
					}
					return InteractionResult.SUCCESS;
				}
				if (actor.getUUID().equals(LEASH.get(targetId))) {
					LEASH.remove(targetId);
					if (TRANSPORT.remove(targetId) != null) entity.stopRiding();
					tell(actor, "You released " + name + " from the leash.");
					tellVictim(server, targetId, "You were released from the leash.");
				} else {
					LEASH.put(targetId, actor.getUUID());
					tell(actor, "You leashed " + name + ". Click again to release, sneak + click to carry, click a boat to load them.");
					tellVictim(server, targetId, "You are on a leash!");
				}
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.SHACKLE -> {
				if (!targetCuffed) { actionBar(actor, "Only handcuffed players can be shackled."); return InteractionResult.FAIL; }
				Anchor a = ANCHOR_PENDING.remove(actor.getUUID());
				if (a != null) {
					ANCHOR.put(targetId, a);
					tell(actor, "You shackled " + name + " to the wall (" + (int) num("anchor_radius") + " block radius).");
					tellVictim(server, targetId, "You are chained to a wall!");
				} else if (ANCHOR.remove(targetId) != null) {
					tell(actor, "You unshackled " + name + ".");
					tellVictim(server, targetId, "The shackle was removed.");
				} else {
					actionBar(actor, "First right-click a BLOCK with the shackle to choose the anchor.");
					return InteractionResult.FAIL;
				}
				return InteractionResult.SUCCESS;
			}
			default -> {
				if (!held.isEmpty()) return InteractionResult.PASS;
				if (ts == null && !(entity instanceof ServerPlayer)) return InteractionResult.PASS;
				if (ts != null) {
					boolean sneak = actor.isShiftKeyDown();
					if (sneak && ts.covered) { removeCover(server, actor, targetId, ts, name); return InteractionResult.SUCCESS; }
					if (ts.gag != null) { removeGag(server, actor, targetId, ts, name); return InteractionResult.SUCCESS; }
					if (ts.earmuffs) { removeEarmuffs(server, actor, targetId, ts, name); return InteractionResult.SUCCESS; }
					if (ts.covered) { removeCover(server, actor, targetId, ts, name); return InteractionResult.SUCCESS; }
				}
				if (entity instanceof ServerPlayer tp && (targetCuffed || isKo(targetId)) && on("feat_patdown")) {
					if (!pref(targetId, "allow_patdown")) { actionBar(actor, "That player doesn't allow being searched."); return InteractionResult.FAIL; }
					actor.openMenu(new SimpleMenuProvider(
						(id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, tp.getInventory(), 4),
						Component.literal(name + "'s pockets")));
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS;
			}
		}
	}

	private static void removeGag(MinecraftServer server, ServerPlayer actor, UUID targetId, State ts, String name) {
		String g = ts.gag;
		ts.gag = null;
		STORE.cleanup(targetId);
		give(actor, KidnapItems.create(g.equals("tape") ? KidnapItems.DUCT_TAPE : (g.equals("cloth") ? KidnapItems.GAG : KidnapItems.MASK)));
		tell(actor, "You removed the gag from " + name + ".");
		tellVictim(server, targetId, "Your gag was removed.");
	}

	private static void removeEarmuffs(MinecraftServer server, ServerPlayer actor, UUID targetId, State ts, String name) {
		ts.earmuffs = false;
		STORE.cleanup(targetId);
		give(actor, KidnapItems.create(KidnapItems.EARMUFFS));
		tell(actor, "You removed the earmuffs from " + name + ".");
		tellVictim(server, targetId, "The earmuffs were removed.");
	}

	private static void removeCover(MinecraftServer server, ServerPlayer actor, UUID targetId, State ts, String name) {
		ts.covered = false;
		STORE.cleanup(targetId);
		give(actor, KidnapItems.create(KidnapItems.HEAD_COVER));
		tell(actor, "You removed the head cover from " + name + ".");
		ServerPlayer target = server.getPlayerList().getPlayer(targetId);
		if (target != null) target.removeEffect(MobEffects.BLINDNESS);
		tellVictim(server, targetId, "The head cover was removed.");
	}

	private InteractionResult workOnCuffs(MinecraftServer server, ServerPlayer actor, ItemStack held, Entity entity,
			UUID targetId, String name, boolean pick) {
		State ts = STORE.get(targetId);
		if (ts == null || (ts.handKey == null && ts.legKey == null)) {
			actionBar(actor, "Nothing to work on.");
			return InteractionResult.FAIL;
		}
		boolean legs = ts.handKey == null || (actor.isShiftKeyDown() && ts.legKey != null);
		String tier = legs ? ts.legTier : ts.handTier;
		if (tier == null) tier = "iron";
		double baseSecs = pick ? num(legs ? "pick_leg_s" : "pick_hand_s") : num(legs ? "file_leg_s" : "file_hand_s");
		int required = (int) (baseSecs * 20 * tierMult(tier));

		String key = actor.getUUID() + ":" + targetId + ":" + (legs ? "l" : "h") + (pick ? "p" : "f");
		long now = actor.level().getGameTime();
		long[] pr = FILING.get(key);
		if (pr == null || now - pr[1] > 25) pr = new long[] {0, now};
		else { pr[0] += now - pr[1]; pr[1] = now; }
		FILING.put(key, pr);

		String what = legs ? "leg cuffs" : "handcuffs";
		if (pr[0] >= required) {
			FILING.remove(key);
			if (pick && actor.getRandom().nextDouble() * 100.0 >= num("pick_success")) {
				held.shrink(1);
				tell(actor, "Your lockpick snapped!");
				sfx(actor, null, 1.0f, "entity.item.break");
				return InteractionResult.SUCCESS;
			}
			if (legs) { ts.legKey = null; ts.legTier = null; } else { ts.handKey = null; ts.handTier = null; }
			STORE.cleanup(targetId);
			lockClick(entity, false);
			if (pick) {
				if (!tier.equals("zip")) give(actor, KidnapItems.create(legs ? KidnapItems.LEGCUFFS : KidnapItems.HANDCUFFS, tier));
				tell(actor, "Click! You picked " + name + "'s " + what + ".");
			} else {
				held.shrink(1);
				tell(actor, "You filed through " + name + "'s " + what + "! The filing tool broke.");
			}
			tellVictim(server, targetId, "Your " + what + " " + (pick ? "were picked open!" : "were filed off!"));
			if (!legs) broadcast(server, name + " was freed by " + actor.getName().getString() + ".", entity);
		} else {
			progress(actor, (pick ? "Picking " : "Filing ") + what + "... " + (int) (pr[0] * 100 / Math.max(1, required)) + "%  (keep holding right-click)");
		}
		return InteractionResult.SUCCESS;
	}

	private static boolean trySelfUnlock(ServerPlayer p, ItemStack held) {
		if (!enabled() || !on("feat_self_unlock")) return false;
		String kind = KidnapItems.kindOf(held);
		if (!kind.equals(KidnapItems.HAND_KEY) && !kind.equals(KidnapItems.LEG_KEY)) return false;
		State s = STORE.get(p.getUUID());
		if (s == null) return false;
		String k = KidnapItems.keyOf(held);
		if (kind.equals(KidnapItems.HAND_KEY) && s.handKey != null && s.handKey.equals(k)) {
			String tier = s.handTier == null ? "iron" : s.handTier;
			s.handKey = null; s.handTier = null;
			STORE.cleanup(p.getUUID());
			held.shrink(1);
			give(p, KidnapItems.create(KidnapItems.HANDCUFFS, tier));
			lockClick(p, false);
			tell(p, "You unlocked your own handcuffs!");
			return true;
		}
		if (kind.equals(KidnapItems.LEG_KEY) && s.handKey == null && s.legKey != null && s.legKey.equals(k)) {
			String tier = s.legTier == null ? "iron" : s.legTier;
			s.legKey = null; s.legTier = null;
			STORE.cleanup(p.getUUID());
			held.shrink(1);
			give(p, KidnapItems.create(KidnapItems.LEGCUFFS, tier));
			lockClick(p, false);
			tell(p, "You unlocked your own leg cuffs!");
			return true;
		}
		return false;
	}

	// =====================================================================
	// commands
	//   /kidnapmod give <item>            (op)
	//   /kidnapmod free <player>          (op)
	//   /kidnapmod config <key> [value]   (op)   /kidnapmod config reset
	//   /kidnapmod pref <key> <value>     (everyone, for themselves)
	//   /kidnapmod sync
	// =====================================================================

	private static Double parseValue(Settings.Def d, String raw) {
		String v = raw.toLowerCase(Locale.ROOT);
		if (d.bool()) {
			if (v.equals("true") || v.equals("on") || v.equals("1") || v.equals("yes")) return 1.0;
			if (v.equals("false") || v.equals("off") || v.equals("0") || v.equals("no")) return 0.0;
			return null;
		}
		try {
			return Settings.clamp(d, Double.parseDouble(v));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		// If `Commands.hasPermission(...)` doesn't compile, replace OP with: src -> src.hasPermission(2)
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("kidnapmod");

		LiteralArgumentBuilder<CommandSourceStack> give = Commands.literal("give").requires(OP);
		for (String name : KidnapItems.GIVEABLE) {
			give.then(Commands.literal(name).executes(ctx -> {
				ctx.getSource().getPlayerOrException();
				give(ctx.getSource().getPlayerOrException(), KidnapItems.createByName(name));
				return 1;
			}));
		}
		root.then(give);

		root.then(Commands.literal("free").requires(OP).then(Commands.argument("target", EntityArgument.player()).executes(ctx -> {
			ServerPlayer t = EntityArgument.getPlayer(ctx, "target");
			UUID id = t.getUUID();
			LEASH.remove(id); ANCHOR.remove(id); TRANSPORT.remove(id); KO.remove(id);
			if (CARRY.containsKey(id)) stopCarry(ctx.getSource().getServer(), id);
			t.stopRiding();
			STORE.clear(id);
			ctx.getSource().sendSuccess(() -> Component.literal("Freed " + t.getName().getString()), true);
			return 1;
		})));

		root.then(Commands.literal("config").requires(OP)
			.then(Commands.literal("reset").executes(ctx -> {
				Settings.SERVER.reset();
				syncAll(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.literal("Kidnap Mod server settings reset to defaults."), true);
				return 1;
			}))
			.then(Commands.argument("key", StringArgumentType.word())
				.executes(ctx -> {
					String key = StringArgumentType.getString(ctx, "key");
					Settings.Def d = Settings.find(Settings.Scope.SERVER, key);
					if (d == null) { ctx.getSource().sendFailure(Component.literal("Unknown setting: " + key)); return 0; }
					ctx.getSource().sendSuccess(() -> Component.literal(d.label() + " (" + key + ") = " + Settings.SERVER.num(key) + "  - " + d.desc()), false);
					return 1;
				})
				.then(Commands.argument("value", StringArgumentType.word()).executes(ctx -> {
					String key = StringArgumentType.getString(ctx, "key");
					Settings.Def d = Settings.find(Settings.Scope.SERVER, key);
					if (d == null) { ctx.getSource().sendFailure(Component.literal("Unknown setting: " + key)); return 0; }
					Double v = parseValue(d, StringArgumentType.getString(ctx, "value"));
					if (v == null) { ctx.getSource().sendFailure(Component.literal("Invalid value for " + key)); return 0; }
					Settings.SERVER.set(key, v);
					Settings.SERVER.save();
					syncAll(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(d.label() + " = " + Settings.SERVER.num(key)), true);
					return 1;
				}))));

		root.then(Commands.literal("pref").then(Commands.argument("key", StringArgumentType.word())
			.then(Commands.argument("value", StringArgumentType.word()).executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				String key = StringArgumentType.getString(ctx, "key");
				Settings.Def d = Settings.find(Settings.Scope.PREFS, key);
				if (d == null) { ctx.getSource().sendFailure(Component.literal("Unknown preference: " + key)); return 0; }
				Double v = parseValue(d, StringArgumentType.getString(ctx, "value"));
				if (v == null) { ctx.getSource().sendFailure(Component.literal("Invalid value for " + key)); return 0; }
				Settings.setPref(p.getUUID(), key, v);
				sendSync(p);
				return 1;
			}))));

		root.then(Commands.literal("sync").executes(ctx -> {
			sendSync(ctx.getSource().getPlayerOrException());
			return 1;
		}));

		dispatcher.register(root);
	}
}
