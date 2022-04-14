package nl.dirkkok.playerbot;

import com.mojang.authlib.GameProfile;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

@Config(name = PlayerBotMod.MODID)
public class ModConfig implements ConfigData {
	public String botToken;

	public HashMap<Long, SerializedGameProfile> players = new HashMap<>();
}
