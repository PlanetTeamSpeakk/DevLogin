package com.ptsmods.devlogin;

import com.google.gson.Gson;
import com.mojang.authlib.Agent;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.util.UUIDTypeAdapter;
import joptsimple.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DevLogin {
    private static final Logger LOG = LogManager.getLogger("DevLogin");

    /**
     * Does the actual args modification. Takes in an array of args and puts out an array of (possibly modified) args.
     * @param args The args to modify
     * @return An array of (possibly modified) args
     */
    public static String[] modifyArgs(String[] args) {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        NonOptionArgumentSpec<String> nonOptionsSpec = parser.nonOptions(); // Used to get any args we don't need, but were passed.
        ArgumentAcceptingOptionSpec<String> usernameSpec = parser.accepts("username").withRequiredArg();
        ArgumentAcceptingOptionSpec<String> passwordSpec = parser.accepts("password").withRequiredArg();
        ArgumentAcceptingOptionSpec<String> mimicPlayerSpec = parser.accepts("mimicPlayer").withRequiredArg();
        // MSA-related args don't need any values
        parser.accepts("msa");
        parser.accepts("msa-nostore");
        parser.accepts("msa-no-dialog");

        // Proxy-related specs
        parser.accepts("proxyHost").withRequiredArg();
        ArgumentAcceptingOptionSpec<Integer> proxyPortSpec = parser.accepts("proxyPort").withRequiredArg().ofType(Integer.class).defaultsTo(8080);
        parser.accepts("proxyUser").withRequiredArg();
        parser.accepts("proxyPass").withRequiredArg();

        OptionSet options = parser.parse(args);
        Proxy proxy = getProxy(options, proxyPortSpec);

        Map<String, String> env = System.getenv();
        // Get the AuthenticationProfile that fits the arguments passed.
        // The priority is as follows: mimicking -> msa -> moj
        AuthenticationProfile profile = options.has(mimicPlayerSpec) ? mimicPlayer(proxy, options.valueOf(mimicPlayerSpec)) : // Mimic player
                options.has("msa") || options.has("msa-nostore") ? // MSA login
                        loginMSA(proxy, options.has("msa"), options.has("msa-no-dialog")) :
                options.has(usernameSpec) && options.has(passwordSpec) || // Moj login
                        env.containsKey("MinecraftUsername") && env.containsKey("MinecraftPassword") ? //
                        loginMoj(proxy, options.has(usernameSpec) ? options.valueOf(usernameSpec) : env.get("MinecraftUsername"),
                                options.has(passwordSpec) ? options.valueOf(passwordSpec) : env.get("MinecraftPassword")) : null;

        Map<String, List<String>> newArgs = parseExcessArgs(options.valuesOf(nonOptionsSpec));
        if (profile != null) profile.put(newArgs);

        // Flatmap the args.
        // In case an arg only has one value (which will be the case most of the time)
        // this will just be turned into '--arg value'
        // If the arg has multiple values, this will be turned into '--arg value1 --arg value2',
        // which is the only method of multiple values supported by JOptSimple
        return newArgs.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .filter(Objects::nonNull)
                        .flatMap(s -> Stream.of("--" + entry.getKey(), s)))
                .toArray(String[]::new);
    }

    /**
     * Parses the proxy from the passed args or {@link Proxy#NO_PROXY} if no proxy was specified.
     * @param options The parsed OptionSet
     * @param proxyPortSpec the proxy port spec as that's an int and not a string
     * @return Either {@link Proxy#NO_PROXY} if no proxy was passed, else the parsed proxy.
     */
    private static Proxy getProxy(OptionSet options, ArgumentAcceptingOptionSpec<Integer> proxyPortSpec) {
        Proxy proxy = Proxy.NO_PROXY;

        String proxyHost = (String) options.valueOf("proxyHost");
        Integer proxyPort = options.valueOf(proxyPortSpec);
        if (proxyHost != null) proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));

        String proxyUser = (String) options.valueOf("proxyUser");
        String proxyPass = (String) options.valueOf("proxyPass");
        if (!proxy.equals(Proxy.NO_PROXY) && proxyUser != null && !proxyUser.isEmpty() && proxyPass != null && !proxyPass.isEmpty())
            // This will be overwritten once we progress in the logic of the Main#main(String[]) method, but until then, use this.
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxyUser, proxyPass.toCharArray());
                }
            });
        return proxy;
    }

    /**
     * Parses all args that are not used by DevLogin, but likely are used by Minecraft.
     * Parsing these args is the best way to override some arguments when needed (like accessToken)
     * @param nonOptions The values of the NonOptions argument spec.
     * @return A map with argument names keys and a list of argument values as values.
     * Afaik, no arguments support/require multiple values, but in case anyone does, the support is there.
     */
    private static Map<String, List<String>> parseExcessArgs(List<String> nonOptions) {
        OptionParser excessParser = new OptionParser();

        nonOptions.stream()
                .filter(s -> s.startsWith("--"))
                .map(s -> s.substring(2))
                .forEach(s -> excessParser.accepts(s, s).withOptionalArg());

        Function<Map.Entry<OptionSpec<?>, List<?>>, String> keyMapper = entry -> ((ArgumentAcceptingOptionSpec<?>) entry.getKey()).description();
        Function<Map.Entry<OptionSpec<?>, List<?>>, List<String>> valueMapper = entry -> entry.getValue().stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
        BinaryOperator<List<String>> mergeFunction = (list1, list2) -> {
            list1.addAll(list2);
            return list1;
        };

        OptionSet excessOptions = excessParser.parse(nonOptions.toArray(new String[0]));
        return excessOptions.asMap().entrySet().stream()
                .collect(Collectors.toMap(keyMapper, valueMapper, mergeFunction, LinkedHashMap::new));
    }

    /**
     * Try to log in using the legacy Mojang way.
     * Probably doesn't work anymore anyway, but I'll leave it in just in case.
     * @param proxy The proxy to use when logging in.
     * @param username The username to log in with
     * @param password The password to log in with
     * @return Either an {@link AuthenticationProfile} containing the necessary args to login or
     * {@code null} if the login was unsuccessful
     */
    private static AuthenticationProfile loginMoj(Proxy proxy, String username, String password) {
        UserAuthentication auth = new YggdrasilAuthenticationService(proxy, UUID.randomUUID().toString()).createUserAuthentication(Agent.MINECRAFT);
        auth.setUsername(username);
        auth.setPassword(password);

        try {
            auth.logIn();
            if (auth.getAvailableProfiles().length == 0) throw new AuthenticationException("No valid game profile was found for the given account.");
        } catch (AuthenticationException e) {
            LOG.error("Could not login using Mojang account.", e);
            return null;
        }

        LOG.info("Logged in as " + auth.getSelectedProfile().getName() + " using a Mojang account.");
        return new AuthenticationProfile(auth.getSelectedProfile().getName(), auth.getSelectedProfile().getId(), auth.getAuthenticatedToken(),
                AuthenticationProfile.Type.valueOf(auth.getUserType().getName().toUpperCase(Locale.ROOT)), null);
    }

    /**
     * Try to log in using the modern MSA way.
     * Will open a dialog with a code used to link your Microsoft account or
     * will print it in the console if {@code noDialog} is {@code true}.
     * Either way, will block the main thread until the user has logged in.
     * @param proxy The proxy to do all requests with
     * @param store Whether to store the refresh token in a file
     * @param noDialog Whether to print the code in the console or show a dialog containing it.
     * @return Either an {@link AuthenticationProfile} or {@code null} if the login was unsuccessful
     */
    private static AuthenticationProfile loginMSA(Proxy proxy, boolean store, boolean noDialog) {
        try {
            try {
                MSA.login(proxy, store, noDialog);
            } catch (Exception e) {
                LOG.error("Could not login using Microsoft account.", e);
                return null;
            }

            if (!MSA.isLoggedIn() || MSA.getProfile() == null) {
                final String message = "Either something went wrong or the account you used to login does not own Minecraft.";
                if (noDialog) LOG.error(message);
                else MSA.showDialog("DevLogin MSA Authentication - error", message);
                return null;
            }

            MSA.MinecraftProfile profile = MSA.getProfile();
            LOG.info("Logged in as " + profile.getName() + " using a Microsoft account.");
            return new AuthenticationProfile(profile.getName(), profile.getUuid(), profile.getToken(), AuthenticationProfile.Type.MSA, null);
        } finally {
            MSA.cleanup();
        }
    }

    /**
     * Gets the game profile of the passed player and stores it in an {@link AuthenticationProfile}.
     * Doesn't actually log in in any way, but at least you won't be a Player572 with an Alex skin.
     * @param proxy The proxy to do all requests with
     * @param mimicPlayer The player to mimic, either a string res of a UUID or a username.
     * @return An {@link AuthenticationProfile} with the username and game profile of the passed player or
     * {@code null} if no Minecraft account could be found for the given UUID/username.
     */
    private static AuthenticationProfile mimicPlayer(Proxy proxy, String mimicPlayer) {
        UUID id;
        try {
            id = UUIDTypeAdapter.fromString(mimicPlayer.replace("-", ""));
        } catch (Exception e) {
            try {
                id = UUIDTypeAdapter.fromString((String) new Gson().fromJson(new BufferedReader(
                        new InputStreamReader(new URL("https://api.mojang.com/users/profiles/minecraft/" + mimicPlayer)
                                .openConnection(proxy).getInputStream())), Map.class).get("id"));
            } catch (IOException e0) {
                LOG.error("Could not find player to mimic, an error occurred.", e);
                return null;
            } catch (NullPointerException e0) {
                LOG.error("The mimicPlayer argument was set to an invalid username/UUID.");
                return null;
            }
        }

        Map<?, ?> data;
        try {
            data = new Gson().fromJson(new BufferedReader(new InputStreamReader(new URL("https://sessionserver.mojang.com/session/minecraft/profile/" +
                    UUIDTypeAdapter.fromUUID(id) + "?unsigned=false").openConnection(proxy).getInputStream())), Map.class);
        } catch (IOException e) {
            LOG.error("Could not get data of the given player.");
            return null;
        }

        LOG.info("Mimicking player " + data.get("name"));
        return new AuthenticationProfile((String) data.get("name"), id, null, null, new Gson().toJson(data.get("properties")));
    }
}
