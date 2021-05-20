package com.ptsmods.devlogin.mixin;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.mojang.authlib.Agent;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.util.UUIDTypeAdapter;
import joptsimple.*;
import net.minecraft.client.main.Main;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This is where all the magic happens, the only class in the mod as of writing this.
 * @author PlanetTeamSpeak
 */
@Mixin(Main.class)
public class MixinMain {
    @Unique private static OptionSpec<String> usernameSpec, passwordSpec, mimicPlayer;
    @Unique private static OptionSet optionSet;
    @Unique private static final Logger LOG = LogManager.getLogger("DevLogin");

    /**
     * Add the password and mimicPlayer optionspecs to the parser.
     * @param args The args Minecraft was launched with (many added by Fabric).
     * @param info The CallbackInfo required for Injects.
     * @param parser The OptionParser used to parse the args.
     */
    @Inject(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionParser;nonOptions()Ljoptsimple/NonOptionArgumentSpec;", remap = false), method = "main", locals = LocalCapture.CAPTURE_FAILHARD)
    private static void addSpecs(String[] args, CallbackInfo info, OptionParser parser) {
        passwordSpec = parser.accepts("password").withRequiredArg();
        mimicPlayer = parser.accepts("mimicPlayer").withRequiredArg();
    }

    /**
     * Redirects all {@link OptionSpecBuilder#withRequiredArg()} calls to check if the
     * {@link OptionSpecBuilder builder} is the username spec, if so, store it for later use.
     * @param builder The {@link OptionSpecBuilder} that requires a required arg.
     * @return The optionspec as a result of the {@link OptionSpecBuilder#withRequiredArg()} call.
     */
    @Redirect(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionSpecBuilder;withRequiredArg()Ljoptsimple/ArgumentAcceptingOptionSpec;", remap = false), method = "main")
    private static ArgumentAcceptingOptionSpec<String> listenForUsernameSpec(OptionSpecBuilder builder) {
        ArgumentAcceptingOptionSpec<String> spec = builder.withRequiredArg();
        if (builder.options().contains("username")) usernameSpec = spec;
        return spec;
    }

    /**
     * Acquires the optionset required to read the parsed commandline arguments.
     * Also what's responsible for being able to mimic other players, which works
     * for all players, including Mojang employees and yes, Dinnerbone is rendered
     * upside down, I checked. :)
     * @param optionParser The parser used to parse the arguments.
     * @param arguments The arguments to parse.
     * @return The parsed optionset.
     */
    @Redirect(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionParser;parse([Ljava/lang/String;)Ljoptsimple/OptionSet;", remap = false), method = "main")
    private static OptionSet getOptionsSet(OptionParser optionParser, String[] arguments) {
        optionSet = optionParser.parse(arguments);
        if (optionSet.has(mimicPlayer)) {
            UUID id;
            try {
                id = UUIDTypeAdapter.fromString(optionSet.valueOf(mimicPlayer).replace("-", ""));
            } catch (Exception e) {
                try {
                    id = UUIDTypeAdapter.fromString((String) new Gson().fromJson(new BufferedReader(new InputStreamReader(new URL("https://api.mojang.com/users/profiles/minecraft/" + optionSet.valueOf(mimicPlayer)).openStream())), Map.class).get("id"));
                } catch (IOException e0) {
                    LOG.error("Could not find player to mimic, an error occurred.", e);
                    return optionSet;
                } catch (NullPointerException e0) {
                    LOG.error("The mimicPlayer argument was set to an invalid playername/UUID.");
                    return optionSet;
                }
            }

            Map<?, ?> data;
            try {
                data = new Gson().fromJson(new BufferedReader(new InputStreamReader(new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + UUIDTypeAdapter.fromUUID(id) + "?unsigned=false").openStream())), Map.class);
            } catch (IOException e) {
                LOG.error("Could not get data of the given player.");
                return optionSet;
            }

            String username = (String) data.get("name");
            List<?> properties = (List<?>) data.get("properties");

            List<String> argsList = Lists.newArrayList(arguments);
            argsList.add("--username");
            argsList.add(username);
            argsList.add("--uuid");
            argsList.add(id.toString());
            argsList.add("--profileProperties");
            argsList.add('"' + new Gson().toJson(properties).replace("\"", "\\\"") + '"');

            LOG.info("Mimicking player " + username);
            optionSet = optionParser.parse(argsList.toArray(new String[0]));
        }
        return optionSet;
    }

    /**
     * Modifies the Session data for if you logged in with your username and password.
     * @param args The args to modify
     */
    @ModifyArgs(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Session;<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"), method = "main")
    private static void modifySessionArgs(Args args) {
        if (optionSet.has(usernameSpec) && optionSet.has(passwordSpec)) {
            UserAuthentication auth = new YggdrasilAuthenticationService(Proxy.NO_PROXY, UUID.randomUUID().toString()).createUserAuthentication(Agent.MINECRAFT);
            auth.setUsername(optionSet.valueOf(usernameSpec));
            auth.setPassword(optionSet.valueOf(passwordSpec));

            try {
                auth.logIn();
                if (auth.getAvailableProfiles().length == 0) throw new AuthenticationException("No valid gameprofile found for the given account.");
            } catch (AuthenticationException e) {
                LOG.warn("Could not login with the given credentials, are they correct?", e);
                return;
            }

            args.setAll(auth.getSelectedProfile().getName(), UUIDTypeAdapter.fromUUID(auth.getSelectedProfile().getId()), auth.getAuthenticatedToken(), auth.getUserType().getName());
        }
    }
}
