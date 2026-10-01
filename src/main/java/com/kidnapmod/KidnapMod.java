package com.kidnapmod;

import com.kidnapmod.KidnapStore.State;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

public class KidnapMod implements ModInitializer {
	public static final String MOD_ID = "kidnapmod";

	// ---- tweakables ----
	/** -0.977 => about 10 seconds per block. Make it closer to -1.0 for even slower. */
	static final double LEG_SLOW = -0.977;
	static final int FILE_TICKS_HANDCUFFS = 20 * 45; // 45 s of continuous filing
	static final int FILE_TICKS_LEGCUFFS = 20 * 30;  // 30 s
	static final String MUFFLE = "mfmhmf";

	static final Identifier SLOW_ID = Identifier.fromNamespaceAndPath(MOD_ID, "leg_cuff_slow");
	static final Identifier NOJUMP_ID = Identifier.fromNamespaceAndPath(MOD_ID, "leg_cuff_nojump");

	public static final KidnapStore STORE = new KidnapStore();

	private static final Set<UUID> SPAWNING = new HashSet<>();
	private static final List<Entity> PENDING_DISCARD = new ArrayList<>();
	/** key = actor:target:type -> [progressTicks, lastGameTime] */
	private static final Map<String, long[]> FILING = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(STORE::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> STORE.save());
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		// --- offline body ---
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> spawnBody(server, handler.getPlayer()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.getPlayer().getUUID();
			for (UUID bodyId : STORE.bodiesOf(id)) {
				for (ServerLevel lvl : server.getAllLevels()) {
					Entity e = lvl.getEntity(bodyId);
					if (e != null) PENDING_DISCARD.add(e);
				}
			}
		});
		// bodies in chunks that load later (e.g. player rejoined before the chunk was loaded)
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			UUID owner = bodyOwner(entity);
			if (owner != null && !SPAWNING.contains(owner) && world.getServer().getPlayerList().getPlayer(owner) != null) {
				PENDING_DISCARD.add(entity);
			}
		});

		// --- handcuff restrictions ---
		AttackBlockCallback.EVENT.register((p, lvl, hand, pos, dir) -> isHandcuffed(p) ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((lvl, p, pos, state, be) -> !isHandcuffed(p));
		AttackEntityCallback.EVENT.register((p, lvl, hand, entity, hit) -> isHandcuffed(p) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((p, lvl, hand) -> isHandcuffed(p) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((p, lvl, hand, hit) -> {
			ItemStack held = p.getItemInHand(hand);
			// our items must never be placed / used on blocks (black wool, tripwire hook, lead on fences ...)
			if (!KidnapItems.kindOf(held).isEmpty()) return InteractionResult.FAIL;
			// handcuffed players can still open doors with an empty hand, but nothing with an item in hand
			if (isHandcuffed(p) && !held.isEmpty()) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});

		// --- applying / removing items on bodies and players ---
		UseEntityCallback.EVENT.register(this::onUseEntity);

		// --- duct tape ---
		ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, message) -> {
			if (sender == null) return message;
			State s = STORE.get(sender.getUUID());
			if (s == null || !s.taped) return message;
			return Component.literal(muffle(message.getString()));
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	// =====================================================================
	// helpers
	// =====================================================================

	private static boolean isHandcuffed(Player p) {
		if (!(p instanceof ServerPlayer)) return false;
		State s = STORE.get(p.getUUID());
		return s != null && s.handKey != null;
	}

	private static UUID bodyOwner(Entity e) {
		return STORE.bodyOwner(e.getUUID());
	}

	private static UUID targetOf(Entity e) {
		if (e instanceof ServerPlayer sp) return sp.getUUID();
		return bodyOwner(e);
	}

	private static String muffle(String text) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (char c : text.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				sb.append(MUFFLE.charAt(i++ % MUFFLE.length()));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String newKeyId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static void give(ServerPlayer p, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!p.getInventory().add(stack)) {
			ServerLevel lvl = (ServerLevel) p.level();
			lvl.addFreshEntity(new ItemEntity(lvl, p.getX(), p.getY() + 0.5, p.getZ(), stack));
		}
	}

	private static void actionBar(ServerPlayer p, String msg) {
		p.sendSystemMessage(Component.literal(msg), true);
	}

	private static void tell(ServerPlayer p, String msg) {
		p.sendSystemMessage(Component.literal(msg));
	}

	private static void tellIfOnline(MinecraftServer server, UUID id, String msg) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p != null) tell(p, msg);
	}

	// =====================================================================
	// offline body (vanilla Mannequin entity -> shows the real skin, vanilla clients can see it)
	// =====================================================================

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void spawnBody(MinecraftServer server, ServerPlayer p) {
		if (p == null || !p.isAlive() || p.isSpectator()) return;
		UUID id = p.getUUID();
		String name = p.getName().getString();
		long msb = id.getMostSignificantBits();
		long lsb = id.getLeastSignificantBits();
		String ints = (int) (msb >> 32) + "," + (int) msb + "," + (int) (lsb >> 32) + "," + (int) lsb;

		UUID bodyId = UUID.randomUUID();
		long bm = bodyId.getMostSignificantBits();
		long bl = bodyId.getLeastSignificantBits();
		String bints = (int) (bm >> 32) + "," + (int) bm + "," + (int) (bl >> 32) + "," + (int) bl;

		String nbt = "{UUID:[I;" + bints + "],profile:{name:\"" + esc(name) + "\",id:[I;" + ints + "]}"
			+ ",CustomName:{text:\"" + esc(name) + "\"},CustomNameVisible:1b,hide_description:1b"
			+ ",immovable:1b,Invulnerable:1b,Silent:1b"
			+ ",Rotation:[" + p.getYRot() + "f,0f]}";
		String cmd = String.format(Locale.ROOT, "summon minecraft:mannequin %.4f %.4f %.4f %s",
			p.getX(), p.getY(), p.getZ(), nbt);

		SPAWNING.add(id);
		STORE.addBody(bodyId, id);
		try {
			CommandSourceStack src = server.createCommandSourceStack()
				.withLevel((ServerLevel) p.level())
				.withSuppressedOutput();
			server.getCommands().performPrefixedCommand(src, cmd);
		} catch (Exception e) {
			System.err.println("[kidnapmod] Failed to spawn body for " + name + ": " + e);
		} finally {
			SPAWNING.remove(id);
		}
	}

	// =====================================================================
	// per tick: apply restrictions to online players
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
		for (ServerPlayer p : server.getPlayerList().getPlayers()) tickPlayer(p);
	}

	private void tickPlayer(ServerPlayer p) {
		State s = STORE.get(p.getUUID());
		boolean cuffed = s != null && s.handKey != null;
		boolean legs = s != null && s.legKey != null;
		boolean covered = s != null && s.covered;

		setModifier(p, Attributes.MOVEMENT_SPEED, SLOW_ID, LEG_SLOW, legs);
		setModifier(p, Attributes.JUMP_STRENGTH, NOJUMP_ID, -1.0, legs);

		if (p.tickCount % 10 == 0) {
			// mining fatigue stops the client from even predicting block breaking
			manageEffect(p, MobEffects.MINING_FATIGUE, 9, cuffed);
			manageEffect(p, MobEffects.BLINDNESS, 0, covered);
		}
		if (covered) enforceHeadCover(p);
	}

	private static void setModifier(ServerPlayer p, Holder<Attribute> attr, Identifier id, double amount, boolean on) {
		AttributeInstance inst = p.getAttribute(attr);
		if (inst == null) return;
		boolean has = inst.getModifier(id) != null;
		if (on && !has) {
			inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (!on && has) {
			inst.removeModifier(id);
		}
	}

	/** Our effects are marked "ambient" so we only ever remove our own. */
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

	private static boolean isCover(ItemStack s) {
		return KidnapItems.HEAD_COVER.equals(KidnapItems.kindOf(s));
	}

	/** The wearer cannot take the head cover off: it is forced back onto the head every tick. */
	private static void enforceHeadCover(ServerPlayer p) {
		ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
		if (!head.isEmpty() && !isCover(head)) {
			// they swapped the cover for something else: give their item back
			give(p, head.copy());
			p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			head = ItemStack.EMPTY;
		}
		// purge stray covers (no duplication / no keeping a copy)
		for (int i = 0; i < 36; i++) {
			if (isCover(p.getInventory().getItem(i))) p.getInventory().setItem(i, ItemStack.EMPTY);
		}
		if (isCover(p.getItemBySlot(EquipmentSlot.OFFHAND))) p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		if (isCover(p.containerMenu.getCarried())) p.containerMenu.setCarried(ItemStack.EMPTY);
		if (head.isEmpty()) p.setItemSlot(EquipmentSlot.HEAD, KidnapItems.create(KidnapItems.HEAD_COVER));
	}

	// =====================================================================
	// right-click on a body / player
	// =====================================================================

	private InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer actor)) {
			return InteractionResult.PASS;
		}
		MinecraftServer server = actor.level().getServer();
		ItemStack held = actor.getItemInHand(hand);
		String kind = KidnapItems.kindOf(held);
		UUID targetId = targetOf(entity);

		if (isHandcuffed(actor)) {
			if (!held.isEmpty() || targetId != null) {
				actionBar(actor, "You are handcuffed!");
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		}
		if (targetId == null) return kind.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;
		if (targetId.equals(actor.getUUID())) return kind.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;

		boolean offline = bodyOwner(entity) != null;
		String name = entity.getName().getString();
		State ts = STORE.get(targetId);
		boolean targetCuffed = ts != null && ts.handKey != null;

		switch (kind) {
			case KidnapItems.HANDCUFFS -> {
				if (!offline) { actionBar(actor, "You can only handcuff players who are OFFLINE."); return InteractionResult.FAIL; }
				if (targetCuffed) { actionBar(actor, name + " is already handcuffed."); return InteractionResult.FAIL; }
				String keyId = newKeyId();
				STORE.getOrCreate(targetId).handKey = keyId;
				STORE.save();
				held.shrink(1);
				give(actor, KidnapItems.key(KidnapItems.HAND_KEY, keyId, name));
				tell(actor, "You handcuffed " + name + "! You received the handcuff key.");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.LEGCUFFS -> {
				if (!offline && !targetCuffed) { actionBar(actor, "They must be offline or handcuffed first."); return InteractionResult.FAIL; }
				if (ts != null && ts.legKey != null) { actionBar(actor, name + " already has leg cuffs."); return InteractionResult.FAIL; }
				String keyId = newKeyId();
				STORE.getOrCreate(targetId).legKey = keyId;
				STORE.save();
				held.shrink(1);
				give(actor, KidnapItems.key(KidnapItems.LEG_KEY, keyId, name));
				tell(actor, "You put leg cuffs on " + name + "! You received the leg cuff key.");
				tellIfOnline(server, targetId, "Someone locked leg cuffs on you!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.DUCT_TAPE -> {
				if (!offline && !targetCuffed) { actionBar(actor, "They must be offline or handcuffed first."); return InteractionResult.FAIL; }
				if (ts != null && ts.taped) { actionBar(actor, name + " is already taped."); return InteractionResult.FAIL; }
				STORE.getOrCreate(targetId).taped = true;
				STORE.save();
				held.shrink(1);
				tell(actor, "You taped " + name + "'s mouth.");
				tellIfOnline(server, targetId, "Someone taped your mouth shut!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.HEAD_COVER -> {
				if (!offline && !targetCuffed) { actionBar(actor, "They must be offline or handcuffed first."); return InteractionResult.FAIL; }
				if (ts != null && ts.covered) { actionBar(actor, name + " already has a head cover."); return InteractionResult.FAIL; }
				STORE.getOrCreate(targetId).covered = true;
				STORE.save();
				held.shrink(1);
				tell(actor, "You put a head cover on " + name + ".");
				tellIfOnline(server, targetId, "Someone put a bag over your head!");
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.HAND_KEY -> {
				if (ts != null && ts.handKey != null && ts.handKey.equals(KidnapItems.keyOf(held))) {
					ts.handKey = null;
					STORE.cleanup(targetId);
					held.shrink(1);
					give(actor, KidnapItems.create(KidnapItems.HANDCUFFS));
					tell(actor, "You unlocked " + name + "'s handcuffs.");
					tellIfOnline(server, targetId, "Your handcuffs were unlocked.");
				} else {
					actionBar(actor, "This key doesn't fit any handcuffs on " + name + ".");
				}
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.LEG_KEY -> {
				if (ts != null && ts.legKey != null && ts.legKey.equals(KidnapItems.keyOf(held))) {
					ts.legKey = null;
					STORE.cleanup(targetId);
					held.shrink(1);
					give(actor, KidnapItems.create(KidnapItems.LEGCUFFS));
					tell(actor, "You unlocked " + name + "'s leg cuffs.");
					tellIfOnline(server, targetId, "Your leg cuffs were unlocked.");
				} else {
					actionBar(actor, "This key doesn't fit any leg cuffs on " + name + ".");
				}
				return InteractionResult.SUCCESS;
			}
			case KidnapItems.FILE -> {
				return fileCuffs(server, actor, held, entity, targetId, name);
			}
			default -> {
				// empty hand: remove tape (or head cover when sneaking) - no key needed
				if (!held.isEmpty()) return InteractionResult.PASS;
				if (ts == null) return InteractionResult.PASS;
				boolean coverFirst = actor.isShiftKeyDown();
				if (coverFirst && ts.covered) {
					removeCover(server, actor, targetId, ts, name);
				} else if (ts.taped) {
					removeTape(server, actor, targetId, ts, name);
				} else if (ts.covered) {
					removeCover(server, actor, targetId, ts, name);
				} else {
					return InteractionResult.PASS;
				}
				return InteractionResult.SUCCESS;
			}
		}
	}

	private static void removeTape(MinecraftServer server, ServerPlayer actor, UUID targetId, State ts, String name) {
		ts.taped = false;
		STORE.cleanup(targetId);
		give(actor, KidnapItems.create(KidnapItems.DUCT_TAPE));
		tell(actor, "You removed the duct tape from " + name + ".");
		tellIfOnline(server, targetId, "The duct tape was removed.");
	}

	private static void removeCover(MinecraftServer server, ServerPlayer actor, UUID targetId, State ts, String name) {
		ts.covered = false;
		STORE.cleanup(targetId);
		give(actor, KidnapItems.create(KidnapItems.HEAD_COVER));
		tell(actor, "You removed the head cover from " + name + ".");
		ServerPlayer target = server.getPlayerList().getPlayer(targetId);
		if (target != null) {
			target.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			target.removeEffect(MobEffects.BLINDNESS);
			tell(target, "The head cover was removed.");
		}
	}

	/** Hold right-click on the target with the file. Progress resets if you stop for more than ~1 second. */
	private InteractionResult fileCuffs(MinecraftServer server, ServerPlayer actor, ItemStack held, Entity entity, UUID targetId, String name) {
		State ts = STORE.get(targetId);
		if (ts == null || (ts.handKey == null && ts.legKey == null)) {
			actionBar(actor, "Nothing to file off.");
			return InteractionResult.FAIL;
		}
		// sneak = file the leg cuffs (when both are on)
		boolean legs = ts.handKey == null || (actor.isShiftKeyDown() && ts.legKey != null);
		int required = legs ? FILE_TICKS_LEGCUFFS : FILE_TICKS_HANDCUFFS;

		String key = actor.getUUID() + ":" + targetId + ":" + (legs ? "l" : "h");
		long now = actor.level().getGameTime();
		long[] pr = FILING.get(key);
		if (pr == null || now - pr[1] > 25) {
			pr = new long[] {0, now};
		} else {
			pr[0] += now - pr[1];
			pr[1] = now;
		}
		FILING.put(key, pr);

		if (pr[0] >= required) {
			FILING.remove(key);
			if (legs) ts.legKey = null; else ts.handKey = null;
			STORE.cleanup(targetId);
			held.shrink(1);
			tell(actor, "You filed through " + name + "'s " + (legs ? "leg cuffs" : "handcuffs") + "! The filing tool broke.");
			tellIfOnline(server, targetId, "Your " + (legs ? "leg cuffs were" : "handcuffs were") + " filed off!");
		} else {
			int pct = (int) (pr[0] * 100 / required);
			actionBar(actor, "Filing " + (legs ? "leg cuffs" : "handcuffs") + "... " + pct + "%  (keep holding right-click)");
		}
		return InteractionResult.SUCCESS;
	}

	// =====================================================================
	// commands: /kidnapmod give <item>, /kidnapmod free <player>
	// =====================================================================

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		// If this line fails to compile, use: .requires(src -> src.hasPermission(2))
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("kidnapmod")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

		LiteralArgumentBuilder<CommandSourceStack> give = Commands.literal("give");
		for (String kind : KidnapItems.GIVEABLE) {
			give.then(Commands.literal(kind).executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				give(p, KidnapItems.create(kind));
				return 1;
			}));
		}
		root.then(give);

		root.then(Commands.literal("free").then(Commands.argument("target", EntityArgument.player()).executes(ctx -> {
			ServerPlayer t = EntityArgument.getPlayer(ctx, "target");
			STORE.clear(t.getUUID());
			ctx.getSource().sendSuccess(() -> Component.literal("Freed " + t.getName().getString()), true);
			return 1;
		})));

		dispatcher.register(root);
	}
}
