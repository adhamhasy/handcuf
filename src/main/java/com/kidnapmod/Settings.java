package com.kidnapmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every setting of the mod lives here. The Mod Menu screen is generated from these definitions,
 * so adding a setting = adding one line below.
 *   SERVER = global rules, editable by ops only (stored in config/kidnapmod-server.json)
 *   PREFS  = per-player preferences / consent (stored in config/kidnapmod-prefs.json)
 *   CLIENT = this player's own client options (config/kidnapmod-client.json)
 */
public final class Settings {
	public enum Scope { SERVER, PREFS, CLIENT }

	public record Def(Scope scope, String cat, String key, String label, String desc, boolean bool,
					  double def, double min, double max, double step) {}

	private static final List<Def> ALL = new ArrayList<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Double>>() {}.getType();
	private static final Type PREF_TYPE = new TypeToken<Map<String, Map<String, Double>>>() {}.getType();

	private static void b(Scope s, String cat, String key, String label, boolean def, String desc) {
		ALL.add(new Def(s, cat, key, label, desc, true, def ? 1 : 0, 0, 1, 1));
	}

	private static void n(Scope s, String cat, String key, String label, double def, double min, double max, double step, String desc) {
		ALL.add(new Def(s, cat, key, label, desc, false, def, min, max, step));
	}

	static {
		Scope S = Scope.SERVER;
		// ------------------------------------------------ SERVER: General
		b(S, "General", "enabled", "Mod enabled", true, "Master switch. Off = nobody can be restrained and all effects stop.");
		b(S, "General", "bodies", "Offline bodies", true, "Spawn a body with the player's skin when they log out.");
		b(S, "General", "body_sleeping_pose", "Body sleeps", true, "Offline bodies lie down asleep.");
		b(S, "General", "body_last_seen", "Body 'last seen'", true, "Show a 'Last seen' time under the body's name.");
		b(S, "General", "body_name_visible", "Body name tag", true, "Show the player's name above the body.");
		b(S, "General", "body_invulnerable", "Body invulnerable", true, "Bodies cannot be killed.");
		b(S, "General", "visuals", "Visible restraints", true, "Show cuffs / gag / hood as armor on players and bodies (1.21.2+ clients with the resource pack).");
		b(S, "General", "allow_online_handcuff", "Handcuff online players", false, "DANGEROUS: lets handcuffs / zip ties be used on players who are online and awake.");
		b(S, "General", "require_opt_in", "Require opt-in", false, "Players must enable 'Allow kidnapping' in their own preferences before they can be handcuffed.");
		b(S, "General", "immune_creative", "Creative = immune", false, "Players in creative mode cannot be restrained.");
		b(S, "General", "broadcast_kidnaps", "Broadcast kidnaps", false, "Announce in chat when someone is kidnapped or freed.");
		b(S, "General", "broadcast_coords", "Broadcast coordinates", false, "Include the coordinates in the kidnap announcement.");
		n(S, "General", "auto_release_minutes", "Auto-release (min)", 0, 0, 1440, 5, "Restraints open by themselves after this many real minutes. 0 = never.");
		// ------------------------------------------------ SERVER: Items
		b(S, "Items", "item_handcuffs", "Handcuffs usable", true, "Allow handcuffs.");
		b(S, "Items", "item_legcuffs", "Leg cuffs usable", true, "Allow leg cuffs.");
		b(S, "Items", "item_ziptie", "Zip ties usable", true, "Allow zip ties.");
		b(S, "Items", "item_gag_tape", "Duct tape usable", true, "Allow duct tape.");
		b(S, "Items", "item_gag_cloth", "Cloth gag usable", true, "Allow the cloth gag.");
		b(S, "Items", "item_mask", "Full mask usable", true, "Allow the full mask.");
		b(S, "Items", "item_earmuffs", "Earmuffs usable", true, "Allow earmuffs.");
		b(S, "Items", "item_headcover", "Head cover usable", true, "Allow the head cover.");
		b(S, "Items", "item_file", "Filing tool usable", true, "Allow the filing tool.");
		b(S, "Items", "item_lockpick", "Lockpick usable", true, "Allow the lockpick.");
		b(S, "Items", "item_leash", "Leash usable", true, "Allow the captive leash.");
		b(S, "Items", "item_shackle", "Shackle usable", true, "Allow the wall shackle.");
		b(S, "Items", "item_dart", "Sleeping darts", true, "Allow sleeping darts to knock players out.");
		b(S, "Items", "tier_gold", "Gold cuffs usable", true, "Allow gold-tier cuffs.");
		b(S, "Items", "tier_diamond", "Diamond cuffs usable", true, "Allow diamond-tier cuffs.");
		b(S, "Items", "tier_netherite", "Netherite cuffs usable", true, "Allow netherite-tier cuffs.");
		b(S, "Items", "feat_carry", "Carrying", true, "Allow picking up handcuffed players (leash + sneak).");
		b(S, "Items", "feat_transport", "Boat / minecart transport", true, "Allow loading a leashed captive into a boat, minecart or mount.");
		b(S, "Items", "feat_patdown", "Pat down", true, "Allow searching a handcuffed player's pockets.");
		b(S, "Items", "feat_self_unlock", "Self-unlock with key", true, "A captive can unlock themselves with their own key.");
		// ------------------------------------------------ SERVER: Restrictions
		b(S, "Restrictions", "r_block_mining", "Cuffed: no mining", true, "Handcuffed players cannot break blocks.");
		b(S, "Restrictions", "r_block_placing", "Cuffed: no placing", true, "Handcuffed players cannot place blocks / use blocks with an item in hand.");
		b(S, "Restrictions", "r_block_item_use", "Cuffed: no item use", true, "Handcuffed players cannot use items (eat, shoot, throw...).");
		b(S, "Restrictions", "r_block_attack", "Cuffed: no attacking", true, "Handcuffed players cannot attack.");
		b(S, "Restrictions", "r_block_all_block_use", "Cuffed: no doors/chests", false, "Handcuffed players cannot interact with ANY block (doors, chests...).");
		b(S, "Restrictions", "r_block_sprint", "Cuffed: no sprinting", true, "Handcuffed players cannot sprint.");
		b(S, "Restrictions", "r_mining_fatigue", "Cuffed: mining fatigue", true, "Also apply strong mining fatigue so the client doesn't even predict breaking.");
		n(S, "Restrictions", "leg_seconds_per_block", "Leg cuffs: sec/block", 10, 1, 60, 1, "How many seconds it takes to walk one block in leg cuffs.");
		b(S, "Restrictions", "leg_block_jump", "Leg cuffs: no jumping", true, "Leg-cuffed players cannot jump.");
		b(S, "Restrictions", "leg_clink", "Leg cuffs: chain sound", true, "Chain clink while walking in leg cuffs.");
		b(S, "Restrictions", "cover_blindness", "Head cover blinds", true, "The head cover applies blindness.");
		b(S, "Restrictions", "gag_muffle", "Gags muffle chat", true, "Duct tape / cloth gag turn chat into muffled noises.");
		b(S, "Restrictions", "mask_blocks_chat", "Mask blocks chat", true, "The full mask stops the wearer from chatting at all.");
		b(S, "Restrictions", "earmuffs_mute", "Earmuffs mute sound", true, "Earmuffs silence everything the wearer hears.");
		n(S, "Restrictions", "earmuffs_interval", "Earmuffs interval (ticks)", 2, 1, 10, 1, "How often sounds are cut. Lower = quieter but more packets.");
		// ------------------------------------------------ SERVER: Timing
		n(S, "Timing", "file_hand_s", "File handcuffs (s)", 45, 5, 600, 5, "Seconds of continuous filing for iron handcuffs.");
		n(S, "Timing", "file_leg_s", "File leg cuffs (s)", 30, 5, 600, 5, "Seconds of continuous filing for iron leg cuffs.");
		n(S, "Timing", "pick_hand_s", "Pick handcuffs (s)", 20, 5, 600, 5, "Seconds of lockpicking for iron handcuffs.");
		n(S, "Timing", "pick_leg_s", "Pick leg cuffs (s)", 12, 5, 600, 5, "Seconds of lockpicking for iron leg cuffs.");
		n(S, "Timing", "pick_success", "Lockpick success %", 70, 0, 100, 5, "Chance the pick opens the lock instead of snapping.");
		n(S, "Timing", "zip_escape_s", "Zip tie escape (s)", 30, 5, 300, 5, "How long a captive must sneak to snap a zip tie.");
		n(S, "Timing", "struggle_s", "Handcuff struggle (s)", 0, 0, 600, 5, "Seconds of sneaking to break free of iron handcuffs. 0 = impossible.");
		n(S, "Timing", "struggle_alert_radius", "Struggle alert radius", 0, 0, 64, 2, "Nearby players are warned when a captive struggles. 0 = off.");
		n(S, "Timing", "ko_s", "Sleeping dart (s)", 60, 5, 600, 5, "How long a sleeping dart knocks a player out.");
		n(S, "Timing", "mult_zip", "Zip tie time x", 0.22, 0.05, 2, 0.05, "Filing / picking time multiplier for zip ties.");
		n(S, "Timing", "mult_gold", "Gold time x", 1.5, 0.5, 20, 0.1, "Filing / picking time multiplier for gold cuffs.");
		n(S, "Timing", "mult_diamond", "Diamond time x", 2.2, 0.5, 20, 0.1, "Filing / picking time multiplier for diamond cuffs.");
		n(S, "Timing", "mult_netherite", "Netherite time x", 4.0, 0.5, 20, 0.1, "Filing / picking time multiplier for netherite cuffs.");
		// ------------------------------------------------ SERVER: Leash & Carry
		n(S, "Leash & Carry", "leash_length", "Leash length", 5, 2, 20, 1, "Blocks before the leash starts pulling.");
		n(S, "Leash & Carry", "leash_break", "Leash break distance", 24, 6, 100, 2, "The leash snaps beyond this distance.");
		n(S, "Leash & Carry", "anchor_radius", "Shackle radius", 3, 1, 16, 1, "How far a shackled captive can move from the anchor.");
		b(S, "Leash & Carry", "leash_requires_holding", "Holder must hold leash", false, "The leash lets go if the holder stops holding the leash item.");
		b(S, "Leash & Carry", "leash_particles", "Rope particles", true, "Draw a particle rope for leashes and shackles.");
		b(S, "Leash & Carry", "carry_slows", "Carrying slows you", true, "The carrier is slowed while carrying someone.");
		// ------------------------------------------------ SERVER: Feedback
		b(S, "Feedback", "sfx_lock", "Lock click sounds", true, "Play a click when cuffs lock or unlock.");
		n(S, "Feedback", "sfx_volume", "Sound volume", 0.8, 0, 2, 0.1, "Volume of the mod's sound effects.");
		b(S, "Feedback", "fb_actionbar", "Action-bar messages", true, "Allow action-bar messages (progress, hints).");
		b(S, "Feedback", "fb_notify_victim", "Notify victims", true, "Tell players when something is done to them.");
		b(S, "Feedback", "fb_status_bar", "Status line", true, "Allow the per-player 'status line' while restrained.");

		// ------------------------------------------------ PREFS: Consent
		Scope P = Scope.PREFS;
		b(P, "Consent", "allow_kidnap", "Allow kidnapping me", true, "Others may handcuff / zip-tie / dart you. (Needs to be ON if the server requires opt-in.)");
		b(P, "Consent", "allow_legcuffs", "Allow leg cuffs", true, "Others may put leg cuffs on you.");
		b(P, "Consent", "allow_gags", "Allow gags", true, "Others may put tape, a cloth gag or a mask on you.");
		b(P, "Consent", "allow_earmuffs", "Allow earmuffs", true, "Others may put earmuffs on you.");
		b(P, "Consent", "allow_headcover", "Allow head cover", true, "Others may put a head cover on you.");
		b(P, "Consent", "allow_leash", "Allow leashing", true, "Others may leash you.");
		b(P, "Consent", "allow_shackle", "Allow shackling", true, "Others may chain you to a wall.");
		b(P, "Consent", "allow_carry", "Allow carrying", true, "Others may pick you up.");
		b(P, "Consent", "allow_patdown", "Allow pat down", true, "Others may search your pockets while you're restrained.");
		b(P, "Consent", "allow_darts", "Allow sleeping darts", true, "Sleeping darts can knock you out.");
		b(P, "Display", "status_bar", "Status line", true, "Show what's restraining you in the action bar.");
		b(P, "Display", "notify_restrained", "Messages about me", true, "Chat messages when something is done to you.");
		b(P, "Display", "notify_actions", "Messages about my actions", true, "Chat messages for things you do.");
		b(P, "Display", "show_progress", "Progress bars", true, "Show filing / picking progress in the action bar.");
		b(P, "Display", "hear_own_clink", "Hear my own chains", true, "Hear the clink of your own leg cuffs.");

		// ------------------------------------------------ CLIENT
		Scope C = Scope.CLIENT;
		b(C, "Creative tab", "tab_enabled", "Creative tab", true, "Show the Kidnap Mod creative tab. (Restart needed.)");
		b(C, "Creative tab", "tab_all_tiers", "All cuff tiers", true, "Show gold / diamond / netherite cuffs in the tab. (Restart needed.)");
		b(C, "Creative tab", "tab_special", "Special items", true, "Show zip ties, darts, shackle, lockpick, earmuffs. (Restart needed.)");
	}

	private Settings() {}

	public static List<Def> of(Scope s) {
		List<Def> out = new ArrayList<>();
		for (Def d : ALL) if (d.scope() == s) out.add(d);
		return out;
	}

	public static Def find(Scope s, String key) {
		for (Def d : ALL) if (d.scope() == s && d.key().equals(key)) return d;
		return null;
	}

	public static double clamp(Def d, double v) {
		v = Math.max(d.min(), Math.min(d.max(), v));
		return Math.round(v * 1000.0) / 1000.0;
	}

	// ------------------------------------------------------------------ stores
	public static final class Store {
		private final Scope scope;
		private final String fileName;
		private final Map<String, Double> values = new HashMap<>();

		Store(Scope scope, String fileName) {
			this.scope = scope;
			this.fileName = fileName;
		}

		private Path path() {
			return FabricLoader.getInstance().getConfigDir().resolve(fileName);
		}

		public double num(String key) {
			Double v = values.get(key);
			if (v != null) return v;
			Def d = find(scope, key);
			return d == null ? 0 : d.def();
		}

		public boolean bool(String key) {
			return num(key) > 0.5;
		}

		public void set(String key, double v) {
			Def d = find(scope, key);
			values.put(key, d == null ? v : clamp(d, v));
		}

		/** Every setting with its current (or default) value. */
		public Map<String, Double> effective() {
			Map<String, Double> m = new LinkedHashMap<>();
			for (Def d : of(scope)) m.put(d.key(), num(d.key()));
			return m;
		}

		public void reset() {
			values.clear();
			save();
		}

		public void load() {
			values.clear();
			Path p = path();
			if (!Files.exists(p)) return;
			try (Reader r = Files.newBufferedReader(p)) {
				Map<String, Double> raw = GSON.fromJson(r, MAP_TYPE);
				if (raw != null) raw.forEach(this::set);
			} catch (Exception e) {
				System.err.println("[kidnapmod] could not read " + fileName + ": " + e);
			}
		}

		public void save() {
			try (Writer w = Files.newBufferedWriter(path())) {
				GSON.toJson(effective(), MAP_TYPE, w);
			} catch (Exception e) {
				System.err.println("[kidnapmod] could not write " + fileName + ": " + e);
			}
		}
	}

	public static final Store SERVER = new Store(Scope.SERVER, "kidnapmod-server.json");
	public static final Store CLIENT = new Store(Scope.CLIENT, "kidnapmod-client.json");

	// ------------------------------------------------------------------ per-player preferences
	private static final Map<UUID, Map<String, Double>> PREFS = new HashMap<>();

	public static void loadPrefs() {
		PREFS.clear();
		Path p = FabricLoader.getInstance().getConfigDir().resolve("kidnapmod-prefs.json");
		if (!Files.exists(p)) return;
		try (Reader r = Files.newBufferedReader(p)) {
			Map<String, Map<String, Double>> raw = GSON.fromJson(r, PREF_TYPE);
			if (raw != null) raw.forEach((k, v) -> PREFS.put(UUID.fromString(k), new HashMap<>(v)));
		} catch (Exception e) {
			System.err.println("[kidnapmod] could not read prefs: " + e);
		}
	}

	public static void savePrefs() {
		Map<String, Map<String, Double>> raw = new HashMap<>();
		PREFS.forEach((k, v) -> raw.put(k.toString(), v));
		try (Writer w = Files.newBufferedWriter(FabricLoader.getInstance().getConfigDir().resolve("kidnapmod-prefs.json"))) {
			GSON.toJson(raw, PREF_TYPE, w);
		} catch (Exception e) {
			System.err.println("[kidnapmod] could not write prefs: " + e);
		}
	}

	public static void setPref(UUID id, String key, double v) {
		Def d = find(Scope.PREFS, key);
		if (d == null) return;
		PREFS.computeIfAbsent(id, k -> new HashMap<>()).put(key, clamp(d, v));
		savePrefs();
	}

	/** Explicit value if the player set one, otherwise the default. */
	public static double pref(UUID id, String key) {
		Map<String, Double> m = PREFS.get(id);
		Double v = m == null ? null : m.get(key);
		if (v != null) return v;
		Def d = find(Scope.PREFS, key);
		return d == null ? 0 : d.def();
	}

	public static boolean prefBool(UUID id, String key) {
		return pref(id, key) > 0.5;
	}

	/** null if the player never touched this preference. */
	public static Double prefExplicit(UUID id, String key) {
		Map<String, Double> m = PREFS.get(id);
		return m == null ? null : m.get(key);
	}
}
