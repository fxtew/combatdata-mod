package dev.combatdata.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.combatdata.ui.MenuScreen;

/** The config button in Mod Menu opens the mod menu; closing it goes back to the mod list. Loaded only with Mod Menu. */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MenuScreen::new;
    }
}
