package com.kidnapmod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;

/** What the server last told this client (settings, my preferences, am I allowed to edit). */
public final class ClientData {
	public static boolean connected;
	public static boolean canEdit;
	public static final Map<String, Double> server = new HashMap<>();
	public static final Map<String, Double> prefs = new HashMap<>();

	private ClientData() {}

	public static void apply(String json) {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			canEdit = root.get("edit").getAsBoolean();
			server.clear();
			prefs.clear();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("server").entrySet()) server.put(e.getKey(), e.getValue().getAsDouble());
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("prefs").entrySet()) prefs.put(e.getKey(), e.getValue().getAsDouble());
			connected = true;
		} catch (Exception ex) {
			System.err.println("[kidnapmod] bad sync packet: " + ex);
		}
	}

	public static void reset() {
		connected = false;
		canEdit = false;
		server.clear();
		prefs.clear();
	}
}
