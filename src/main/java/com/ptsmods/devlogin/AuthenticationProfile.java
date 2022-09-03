package com.ptsmods.devlogin;

import net.minecraft.client.util.Session;

import java.util.*;

public class AuthenticationProfile {
    private final String username;
    private final UUID id;
    private final String accessToken;
    private final Session.AccountType type;
    private final String properties;

    public AuthenticationProfile(String username, UUID id, String accessToken, Session.AccountType type, String properties) {
        this.username = username;
        this.id = id;
        this.accessToken = accessToken;
        this.type = type;
        this.properties = properties;
    }

    public void put(Map<String, List<String>> args) {
        args.put("username", Collections.singletonList(getUsername()));
        args.put("uuid", Collections.singletonList(getId().toString()));

        // Access token and type will not be null in case of an actual login,
        // profileProperties will not be null in case of mimicking
        if (getAccessToken() != null) args.put("accessToken", Collections.singletonList(getAccessToken()));
        if (getType() != null) args.put("userType", Collections.singletonList(type.getName()));
        if (getProperties() != null) args.put("profileProperties", Collections.singletonList('"' + getProperties().replace("\"", "\\\"") + '"'));
    }

    public String getUsername() {
        return username;
    }

    public UUID getId() {
        return id;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Session.AccountType getType() {
        return type;
    }

    public String getProperties() {
        return properties;
    }
}
