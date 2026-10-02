package com.kidnapmod.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the "Configure" button for Kidnap Mod in Mod Menu. */
public class KidnapModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return KidnapSettingsScreen::new;
	}
}
