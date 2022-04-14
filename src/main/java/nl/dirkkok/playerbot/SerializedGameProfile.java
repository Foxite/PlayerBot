package nl.dirkkok.playerbot;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

public final class SerializedGameProfile {
	private String name;
	private UUID id;

	SerializedGameProfile(String name, UUID id) {
		this.name = name;
		this.id = id;
	}

	public static SerializedGameProfile of(GameProfile profile) {
		return new SerializedGameProfile(profile.getName(), profile.getId());
	}

	public GameProfile toProfile() {
		return new GameProfile(id, name);
	}

	public String name() {
		return name;
	}

	public UUID id() {
		return id;
	}
}
