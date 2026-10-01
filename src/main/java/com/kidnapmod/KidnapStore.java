package com.kidnapmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Persists who is cuffed / taped / covered in <world>/kidnapmod_state.json (plain JSON, no SavedData API). */
public final class KidnapStore {
	public static final class State {
		public String handKey;      // key id of the handcuffs, null = not handcuffed
		public String legKey;       // key id of the leg cuffs, null = none
		public boolean taped;
		public boolean covered;

		boolean isEmpty() {
			return handKey == null && legKey == null && !taped && !covered;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, State>>() {}.getType();

	private final Map<UUID, State> states = new HashMap<>();
	private Path file;

	public void load(MinecraftServer server) {
		states.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("kidnapmod_state.json");
		if (!Files.exists(file)) return;
		try (Reader r = Files.newBufferedReader(file)) {
			Map<String, State> raw = GSON.fromJson(r, TYPE);
			if (raw != null) raw.forEach((k, v) -> states.put(UUID.fromString(k), v));
		} catch (Exception e) {
			System.err.println("[kidnapmod] Could not read state file: " + e);
		}
	}

	public State get(UUID id) {
		return states.get(id);
	}

	public State getOrCreate(UUID id) {
		return states.computeIfAbsent(id, k -> new State());
	}

	/** Removes the entry if nothing is active anymore, then writes to disk. */
	public void cleanup(UUID id) {
		State s = states.get(id);
		if (s != null && s.isEmpty()) states.remove(id);
		save();
	}

	public void clear(UUID id) {
		states.remove(id);
		save();
	}

	public void save() {
		if (file == null) return;
		Map<String, State> raw = new HashMap<>();
		states.forEach((k, v) -> raw.put(k.toString(), v));
		try (Writer w = Files.newBufferedWriter(file)) {
			GSON.toJson(raw, TYPE, w);
		} catch (Exception e) {
			System.err.println("[kidnapmod] Could not write state file: " + e);
		}
	}
}
