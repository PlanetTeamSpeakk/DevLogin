package com.ptsmods.devlogin;

import java.util.*;

public class AuthenticationProfile {
    private final String username;
    private final UUID id;
    private final String accessToken;
    private final Type type;
    private final String properties;

    public AuthenticationProfile(String username, UUID id, String accessToken, Type type, String properties) {
        this.username = username;
        this.id = id;
        this.accessToken = accessToken;
        this.type = type;
        this.properties = properties;
    }

    /**
     * Puts the data of this authentication profile in an args map
     * @param args The args map to put the data in
     */
    public void put(Map<String, List<String>> args) {
        args.put("username", Collections.singletonList(getUsername()));
        args.put("uuid", Collections.singletonList(getId().toString()));

        // Access token and type will not be null in case of an actual login,
        // profileProperties will not be null in case of mimicking
        if (getAccessToken() != null) args.put("accessToken", Collections.singletonList(getAccessToken()));
        if (getType() != null) args.put("userType", Collections.singletonList(type.name().toLowerCase(Locale.ROOT)));
        if (getProperties() != null) args.put("profileProperties", Collections.singletonList('"' + getProperties().replace("\"", "\\\"") + '"'));
    }

    /**
     * @return The username of the player logging in
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return The UUID of the player logging in
     */
    public UUID getId() {
        return id;
    }

    /**
     * @return The access token to authenticate with
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * @return The type of this authentication profile, either LEGACY or MOJANG when logging in with a Mojang account,
     * MSA when logging in with a Microsoft account or {@code null} when mimicking.
     */
    public Type getType() {
        return type;
    }

    /**
     * @return The profile properties of this authentication profile.
     * Resembles the game profile of a player. Will always be {@code null} when not mimicking
     */
    public String getProperties() {
        return properties;
    }

    public enum Type {
        LEGACY, MOJANG, MSA
    }
}
