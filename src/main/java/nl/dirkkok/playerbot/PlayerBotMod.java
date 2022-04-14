package nl.dirkkok.playerbot;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.ProfileNotFoundException;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.interaction.SlashCommand;
import org.javacord.api.interaction.SlashCommandBuilder;
import org.javacord.api.interaction.SlashCommandInteraction;
import org.javacord.api.interaction.SlashCommandOption;
import org.javacord.api.interaction.callback.InteractionCallbackDataFlag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

public class PlayerBotMod implements ModInitializer {
	public static final String MODID = "playerbot";
	public static final Logger LOGGER = LoggerFactory.getLogger("PlayerBot");
	public static final boolean DEBUG = false;

	private MinecraftServer server;

	@Override
	public void onInitialize() {
		LOGGER.info("Hello!");

		ServerLifecycleEvents.SERVER_STARTING.register((MinecraftServer server) -> this.server = server);

		AutoConfig.register(ModConfig.class, (config, aClass) -> new GsonConfigSerializer<>(config, aClass, new GsonBuilder().setPrettyPrinting().serializeNulls().create()));
		ConfigHolder<ModConfig> holder = AutoConfig.getConfigHolder(ModConfig.class);

		DiscordApi api = new DiscordApiBuilder().setToken(holder.getConfig().botToken).login().join();

		SlashCommandBuilder command = SlashCommand
				.with("whitelist", "Checks the functionality of this command")
				.addOption(SlashCommandOption.createStringOption("username", "your minecraft username", true));
		if (DEBUG) {
			final long devServerId = 0L;
			command.createForServer(api.getServerById(devServerId).get()).join();
		} else {
			command.createGlobal(api).join();
		}

		api.addSlashCommandCreateListener(event -> {
			SlashCommandInteraction sci = event.getSlashCommandInteraction();
			if (sci.getCommandName().equals("whitelist")) {
				onWhitelistCommandUsed(holder, sci);
			}
		});
	}

	private void onWhitelistCommandUsed(ConfigHolder<ModConfig> holder, SlashCommandInteraction sci) {
		String username = sci.getOptionByName("username").get().getStringValue().get();

		ModConfig config = holder.getConfig();

		Optional<Map.Entry<Long, SerializedGameProfile>> existingDesiredProfile = config.players.entrySet().stream().filter(entry -> entry.getValue().name().equalsIgnoreCase(username)).findFirst();
		@Nullable SerializedGameProfile existingProfileByDiscordUser = config.players.getOrDefault(sci.getUser().getId(), null);
		if (existingProfileByDiscordUser != null) {
			// current discord user already has a whitelist entry
			if (existingProfileByDiscordUser.name().equalsIgnoreCase(username)) {
				// it's the same mc name
				onWhitelistCommandCompleted(sci, "You are already whitelisted as %s.".formatted(username));
			} else if (existingDesiredProfile.isPresent()) {
				// the mc name they want is already on another discord id
				onWhitelistCommandCompleted(sci, "%s is already whitelisted by someone else.".formatted(existingDesiredProfile.get().getValue().name()));
			} else {
				// it's a new name, remove their existing one and add the new one
				server.getGameProfileRepo().findProfilesByNames(new String[] { username }, Agent.MINECRAFT, new WhitelistCommandProfileLookupCallback(sci, config.players.get(sci.getUser().getId()).toProfile(), holder));
			}
		} else {
			// current discord user does not have a whitelist entry
			if (existingDesiredProfile.isPresent()) {
				// the one they want is already on another discord id
				onWhitelistCommandCompleted(sci, "%s is already whitelisted by someone else.".formatted(existingDesiredProfile.get().getValue().name()));
			} else {
				// the one they want is not on the list
				server.getGameProfileRepo().findProfilesByNames(new String[] { username }, Agent.MINECRAFT, new WhitelistCommandProfileLookupCallback(sci, null, holder));
			}
		}
	}

	private void onWhitelistCommandCompleted(SlashCommandInteraction sci, String response) {
		sci.createImmediateResponder()
				.setContent(response)
				.setFlags(InteractionCallbackDataFlag.EPHEMERAL) // Only visible for the user which invoked the command
				.respond();
	}

	private ServerCommandSource getBotCommandSource() {
		ServerWorld serverWorld = this.server.getOverworld();

		return new ServerCommandSource(this.server, Vec3d.of(serverWorld.getSpawnPos()), Vec2f.ZERO, serverWorld, 4, "PlayerBot", new LiteralText("PlayerBot"), this.server, null);
	}

	private class WhitelistCommandProfileLookupCallback implements ProfileLookupCallback {
		private final SlashCommandInteraction sci;
		@Nullable
		private final GameProfile oldProfile;
		private final ConfigHolder<ModConfig> holder;

		private WhitelistCommandProfileLookupCallback(SlashCommandInteraction sci, @Nullable GameProfile oldProfile, ConfigHolder<ModConfig> holder) {
			this.sci = sci;
			this.oldProfile = oldProfile;
			this.holder = holder;
		}

		@Override
		public void onProfileLookupSucceeded(GameProfile profile) {
			String response;
			String notificationString;
			if (oldProfile != null) {
				server.getPlayerManager().getWhitelist().remove(oldProfile);
				server.kickNonWhitelistedPlayers(getBotCommandSource());
				notificationString = "Discord user %s (%s): Removed %s from whitelist and added %s".formatted(sci.getUser().getDiscriminatedName(), sci.getUser().getIdAsString(), oldProfile.getName(), profile.getName());
				response = "Updated your minecraft username! Removed %s from whitelist and added %s.".formatted(oldProfile.getName(), profile.getName());
			} else {
				notificationString = "Discord user %s (%s): Added %s to whitelist".formatted(sci.getUser().getDiscriminatedName(), sci.getUser().getIdAsString(), profile.getName());
				response = "You are now whitelisted as %s!".formatted(profile.getName());
			}
			getBotCommandSource().sendFeedback(Text.of(notificationString), true);

			server.getPlayerManager().getWhitelist().add(new WhitelistEntry(profile));
			holder.getConfig().players.put(sci.getUser().getId(), SerializedGameProfile.of(profile));
			holder.save();
			onWhitelistCommandCompleted(sci, response);
		}

		@Override
		public void onProfileLookupFailed(GameProfile profile, Exception exception) {
			if (exception instanceof ProfileNotFoundException) {
				onWhitelistCommandCompleted(sci, "It doesn't look like that username exists, maybe there is a typo?");
			} else {
				LOGGER.error("Error getting profile of %s".formatted(profile.getName()), exception);
				onWhitelistCommandCompleted(sci, "Error getting your minecraft profile: %s".formatted(exception.getMessage()));
			}
		}
	}
}
