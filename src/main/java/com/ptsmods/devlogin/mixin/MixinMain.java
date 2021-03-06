package com.ptsmods.devlogin.mixin;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.mojang.authlib.Agent;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.util.UUIDTypeAdapter;
import com.ptsmods.devlogin.MSA;
import joptsimple.*;
import net.minecraft.client.main.Main;
import net.minecraft.client.util.Session;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This is where all the magic happens, ~~the only class in the mod as of writing this~~.
 * Nvm, a class more than twice as big as this one was required for MSA.
 * @author PlanetTeamSpeak
 */
@Mixin(Main.class)
public class MixinMain {
    @Unique private static OptionSpec<String> usernameSpec, passwordSpec, mimicPlayerSpec;
    @Unique private static OptionSet optionSet;
    @Unique private static final Logger LOG = LogManager.getLogger("DevLogin");
    @Unique private static Proxy proxy = Proxy.NO_PROXY;

    /**
     * Redirects all {@link OptionSpecBuilder#withRequiredArg()} calls to check if the
     * {@link OptionSpecBuilder builder} is the username spec, if so, store it for later use.
     * @param builder The {@link OptionSpecBuilder} that requires a required arg.
     * @return The optionspec as a result of the {@link OptionSpecBuilder#withRequiredArg()} call.
     */
    @Redirect(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionSpecBuilder;withRequiredArg()Ljoptsimple/ArgumentAcceptingOptionSpec;", remap = false), method = "main", remap = false)
    private static ArgumentAcceptingOptionSpec<String> listenForUsernameSpec(OptionSpecBuilder builder) {
        ArgumentAcceptingOptionSpec<String> spec = builder.withRequiredArg();
        if (builder.options().contains("username")) usernameSpec = spec;
        return spec;
    }

    /**
     * Adds the password and mimicPlayer optionspecs to the parser.
     * @param args The args Minecraft was launched with (many added by Fabric).
     * @param info The CallbackInfo required for Injects.
     * @param parser The OptionParser used to parse the args.
     */
    @Inject(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionParser;nonOptions()Ljoptsimple/NonOptionArgumentSpec;", remap = false), method = "main", locals = LocalCapture.CAPTURE_FAILHARD, remap = false)
    private static void addSpecs(String[] args, CallbackInfo info, OptionParser parser) {
        passwordSpec = parser.accepts("password").withRequiredArg();
        mimicPlayerSpec = parser.accepts("mimicPlayer").withRequiredArg();
        parser.accepts("msa");
        parser.accepts("msa-nostore");
		parser.accepts("msa-no-dialog");
    }

    /**
     * Store the proxy for later use when making a new {@link net.minecraft.client.util.Session}
     * if one is required.
     * @param theProxy The proxy to use.
     * @return The same proxy, but now it's stored in a field.
     */
    @ModifyVariable(at = @At("STORE"), method = "main", remap = false)
    private static Proxy storeProxy(Proxy theProxy) {
        return proxy = theProxy;
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
    @Redirect(at = @At(value = "INVOKE", target = "Ljoptsimple/OptionParser;parse([Ljava/lang/String;)Ljoptsimple/OptionSet;", remap = false), method = "main", remap = false)
    private static OptionSet getOptionsSet(OptionParser optionParser, String[] arguments) {
        if (usernameSpec == null) usernameSpec = optionParser.accepts("username").withRequiredArg().defaultsTo("Player" + Util.getMeasuringTimeNano() % 1000); // Just in case it wasn't found.
        optionSet = optionParser.parse(arguments);
        if (optionSet.has(mimicPlayerSpec)) {
            UUID id;
            try {
                id = UUIDTypeAdapter.fromString(optionSet.valueOf(mimicPlayerSpec).replace("-", ""));
            } catch (Exception e) {
                try {
                    id = UUIDTypeAdapter.fromString((String) new Gson().fromJson(new BufferedReader(new InputStreamReader(new URL("https://api.mojang.com/users/profiles/minecraft/" + optionSet.valueOf(mimicPlayerSpec)).openConnection(proxy).getInputStream())), Map.class).get("id"));
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
                data = new Gson().fromJson(new BufferedReader(new InputStreamReader(new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + UUIDTypeAdapter.fromUUID(id) + "?unsigned=false").openConnection(proxy).getInputStream())), Map.class);
            } catch (IOException e) {
                LOG.error("Could not get data of the given player.");
                return optionSet;
            }

            String username = (String) data.get("name");
            List<String> argsList = Lists.newArrayList(arguments);
            argsList.add("--username");
            argsList.add(username);
            argsList.add("--uuid");
            argsList.add(id.toString());
            argsList.add("--profileProperties");
            argsList.add('"' + new Gson().toJson(data.get("properties")).replace("\"", "\\\"") + '"');

            LOG.info("Mimicking player " + username);
            optionSet = optionParser.parse(argsList.toArray(new String[0]));
        }
        return optionSet;
    }

    /**
     * Modifies the Session data for if you logged in with your username and password.
     * For versions older than 1.18 snapshots.
     * @param args The args to modify
     */
    @Group(name = "modifySessionArgs", min = 1, max = 1)
    @ModifyArgs(at = @At(value = "INVOKE", target = "Lnet/minecraft/class_320;<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", remap = false), method = "main", remap = false)
    private static void modifySessionArgsOld(Args args) {
        doModifySessionArgs(args, true);
    }

    /**
     * Modifies the Session data for if you logged in with your username and password.
     * For 1.18 snapshots or newer.
     * @param args The args to modify
     */
    @Group(name = "modifySessionArgs", min = 1, max = 1)
    @ModifyArgs(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Session;<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Optional;Ljava/util/Optional;Lnet/minecraft/client/util/Session$AccountType;)V"), method = "main", remap = false)
    private static void modifySessionArgsNew(Args args) {
        doModifySessionArgs(args, false);
    }

    private static @Unique void doModifySessionArgs(Args args, boolean legacy) {
        boolean fromSpec = optionSet.has(usernameSpec) && optionSet.has(passwordSpec);
        if (optionSet.has("msa") || optionSet.has("msa-nostore")) {
            try {
				boolean noDialog = optionSet.has("msa-no-dialog");
                MSA.login(proxy, optionSet.has("msa"), noDialog);
                if (!MSA.isLoggedIn() || MSA.getProfile() == null) {
					final String message = "Either something went wrong or the account you used to login does not own Minecraft.";
					if (noDialog) LOG.error(message);
					else MSA.showDialog("DevLogin MSA Authentication - error", message);
				} else {
                    MSA.MinecraftProfile profile = MSA.getProfile();
                    if (legacy) args.setAll(profile.getName(), UUIDTypeAdapter.fromUUID(profile.getUuid()), profile.getToken(), "msa");
                    else args.setAll(profile.getName(), UUIDTypeAdapter.fromUUID(profile.getUuid()), profile.getToken(), Optional.<String>empty(), Optional.<String>empty(), Session.AccountType.MSA);
                }
                MSA.cleanup();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        } else if ((fromSpec || System.getenv().containsKey("MinecraftUsername") && System.getenv().containsKey("MinecraftPassword")) && !optionSet.has(mimicPlayerSpec)) {
            UserAuthentication auth = new YggdrasilAuthenticationService(proxy, UUID.randomUUID().toString()).createUserAuthentication(Agent.MINECRAFT);
            auth.setUsername(fromSpec ? optionSet.valueOf(usernameSpec) : System.getenv("MinecraftUsername"));
            auth.setPassword(fromSpec ? optionSet.valueOf(passwordSpec) : System.getenv("MinecraftPassword"));

            try {
                auth.logIn();
                if (auth.getAvailableProfiles().length == 0) throw new AuthenticationException("No valid gameprofile was found for the given account.");
            } catch (AuthenticationException e) {
                LOG.warn("Could not login with the given credentials, are they correct?", e);
                return;
            }

            if (!legacy) args.setAll(auth.getSelectedProfile().getName(), UUIDTypeAdapter.fromUUID(auth.getSelectedProfile().getId()), auth.getAuthenticatedToken(), auth.getUserType().getName());
            else args.setAll(auth.getSelectedProfile().getName(), UUIDTypeAdapter.fromUUID(auth.getSelectedProfile().getId()), auth.getAuthenticatedToken(), args.<Optional<String>>get(3), args.<Optional<String>>get(4), Session.AccountType.byName(auth.getUserType().getName()));
        }
    }
}
