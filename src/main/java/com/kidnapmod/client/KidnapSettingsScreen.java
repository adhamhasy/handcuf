package com.kidnapmod.client;

import com.kidnapmod.Settings;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * Settings screen (opened from Mod Menu). Built only from buttons, generated from Settings definitions.
 *   Server        - global rules; only ops can change them (changes are sent as /kidnapmod config commands)
 *   My Preferences - consent + display options for yourself (/kidnapmod pref)
 *   Client        - options that only affect your own game
 */
public class KidnapSettingsScreen extends Screen {
	private enum Mode {
		SERVER("Server"), PREFS("My Preferences"), CLIENT("Client");
		final String label;
		Mode(String label) { this.label = label; }
	}

	private final Screen parent;
	private Mode mode;
	private int page = 0;

	public KidnapSettingsScreen(Screen parent) {
		super(Component.literal("Kidnap Mod Settings"));
		this.parent = parent;
		this.mode = ClientData.connected ? Mode.SERVER : Mode.CLIENT;
	}

	private Settings.Scope scope() {
		return switch (mode) {
			case SERVER -> Settings.Scope.SERVER;
			case PREFS -> Settings.Scope.PREFS;
			case CLIENT -> Settings.Scope.CLIENT;
		};
	}

	private boolean editable() {
		return switch (mode) {
			case SERVER -> ClientData.connected && ClientData.canEdit;
			case PREFS -> ClientData.connected;
			case CLIENT -> true;
		};
	}

	private double value(Settings.Def d) {
		return switch (mode) {
			case SERVER -> ClientData.server.getOrDefault(d.key(), d.def());
			case PREFS -> ClientData.prefs.getOrDefault(d.key(), d.def());
			case CLIENT -> Settings.CLIENT.num(d.key());
		};
	}

	private static String show(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	private void set(Settings.Def d, double raw) {
		double v = Settings.clamp(d, raw);
		String arg = d.bool() ? (v > 0.5 ? "true" : "false") : String.format(Locale.ROOT, "%s", v);
		switch (mode) {
			case SERVER -> {
				ClientData.server.put(d.key(), v);
				sendCommand("kidnapmod config " + d.key() + " " + arg);
			}
			case PREFS -> {
				ClientData.prefs.put(d.key(), v);
				sendCommand("kidnapmod pref " + d.key() + " " + arg);
			}
			case CLIENT -> {
				Settings.CLIENT.set(d.key(), v);
				Settings.CLIENT.save();
			}
		}
		this.rebuildWidgets();
	}

	private static void sendCommand(String cmd) {
		ClientPacketListener c = Minecraft.getInstance().getConnection();
		if (c != null) c.sendCommand(cmd);
	}

	private Button label(String text, int x, int y, int w) {
		Button b = Button.builder(Component.literal(text), btn -> {}).bounds(x, y, w, 20).build();
		b.active = false;
		return b;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - 150;
		List<Settings.Def> defs = Settings.of(scope());
		boolean usable = mode == Mode.CLIENT || ClientData.connected;

		int perPage = Math.max(3, (this.height - 58 - 34) / 22);
		int pages = Math.max(1, (defs.size() + perPage - 1) / perPage);
		if (page >= pages) page = pages - 1;
		if (page < 0) page = 0;
		int start = page * perPage;

		String cat = (usable && start < defs.size()) ? defs.get(start).cat() : "";
		String ro = (usable && !editable()) ? "  (read-only)" : "";
		this.addRenderableWidget(label("Kidnap Mod - " + mode.label + (cat.isEmpty() ? "" : " - " + cat) + "  " + (page + 1) + "/" + pages + ro, left, 6, 300));

		for (Mode m : Mode.values()) {
			Button mb = Button.builder(Component.literal(m.label), btn -> { mode = m; page = 0; this.rebuildWidgets(); })
				.bounds(left + m.ordinal() * 100, 30, 98, 20).build();
			mb.active = m != mode;
			this.addRenderableWidget(mb);
		}

		if (!usable) {
			this.addRenderableWidget(label("Join a world / server with Kidnap Mod to edit these.", left, 70, 300));
		} else {
			for (int i = 0; i < perPage && start + i < defs.size(); i++) {
				Settings.Def d = defs.get(start + i);
				int y = 58 + i * 22;
				double v = value(d);
				boolean ed = editable();
				Tooltip tip = Tooltip.create(Component.literal(d.desc()));
				if (d.bool()) {
					Button b = Button.builder(Component.literal(d.label() + ": " + (v > 0.5 ? "ON" : "OFF")), btn -> set(d, v > 0.5 ? 0 : 1))
						.bounds(left, y, 300, 20).tooltip(tip).build();
					b.active = ed;
					this.addRenderableWidget(b);
				} else {
					Button name = Button.builder(Component.literal(d.label() + ": " + show(v)), btn -> {})
						.bounds(left, y, 200, 20).tooltip(tip).build();
					this.addRenderableWidget(name);
					Button minus = Button.builder(Component.literal("-"), btn -> set(d, v - d.step())).bounds(left + 204, y, 46, 20).tooltip(tip).build();
					Button plus = Button.builder(Component.literal("+"), btn -> set(d, v + d.step())).bounds(left + 254, y, 46, 20).tooltip(tip).build();
					minus.active = ed && v > d.min();
					plus.active = ed && v < d.max();
					this.addRenderableWidget(minus);
					this.addRenderableWidget(plus);
				}
			}
		}

		int by = this.height - 26;
		Button prev = Button.builder(Component.literal("< Prev"), btn -> { page--; this.rebuildWidgets(); }).bounds(left, by, 98, 20).build();
		prev.active = page > 0;
		Button next = Button.builder(Component.literal("Next >"), btn -> { page++; this.rebuildWidgets(); }).bounds(left + 202, by, 98, 20).build();
		next.active = page < pages - 1;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> this.onClose()).bounds(left + 101, by, 98, 20).build());
		this.addRenderableWidget(next);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
