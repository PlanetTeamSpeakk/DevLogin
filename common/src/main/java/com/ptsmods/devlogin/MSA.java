package com.ptsmods.devlogin;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.util.UUIDTypeAdapter;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// Thanks to https://wiki.vg/Microsoft_Authentication_Scheme, Microsoft Docs and
// https://github.com/MultiMC/Launcher/blob/develop/launcher/minecraft/auth/flows/AuthContext.cpp for this
public class MSA {
    private static final Logger LOG = LogManager.getLogger("DevLogin-MSA");
    private static final ThreadPoolExecutor TPE = new ThreadPoolExecutor(1, 4, 300, TimeUnit.SECONDS, new SynchronousQueue<>());
    private static final Pattern urlPattern = Pattern.compile("<a href=\"(.*?)\">.*?</a>"), tagPattern = Pattern.compile("<([A-Za-z]*?).*?>(.*?)</\\1>");
    private static final File tokenFile = new File("DevLoginCache.json");
    private static final String CLIENT_ID = "bfcbedc1-f14e-441f-a136-15aec874e6c2"; // DevLogin Azure application client id
    private static final Object waitLock = new Object();
    private static Proxy proxy = Proxy.NO_PROXY;
    private static boolean noDialog = false;
    private static JFrame mainDialog;
    private static String deviceCode; // Strings sorted by steps they're acquired in.
    private static String accessToken, refreshToken;
    private static String xblToken, userHash;
    private static String xstsToken;
    private static String mcToken;
    private static MinecraftProfile profile;
    private static boolean isCancelled = false;

    /**
     * Takes all the necessary steps to get a Minecraft token from a Microsoft account.
     * @param proxy The proxy to route requests through.
     * @param storeRefreshToken Whether the refresh token should be stored for later use.
     * @param noDialog Whether to print the code to the console or show a dialog
     * @throws IOException If anything goes wrong with the requests.
     * @throws InterruptedException If the thread gets interrupted while waiting.
     */
    public static void login(Proxy proxy, boolean storeRefreshToken, boolean noDialog) throws IOException, InterruptedException {
        if (!noDialog) System.setProperty("java.awt.headless", "false"); // Can't display dialogs otherwise.
        MSA.proxy = proxy;
        MSA.noDialog = noDialog;

        if (tokenFile.exists()) {
            Map<String, String> data = MoreObjects.firstNonNull(readData(), Collections.emptyMap());
            refreshToken = data.get("refreshToken");
            mcToken = data.get("mcToken");

            if (reqProfile()) {
                LOG.info("Cached token is valid.");
                return;
            }

            if (refreshToken != null) {
                LOG.info("Cached token is invalid, requesting new one using refresh token.");
                refreshToken(b -> {
                    if (!b)
                        try {
                            reqTokens();
                        } catch (IOException ignored) {}
                });
            } else {
                LOG.info("Cached token is invalid.");
                reqTokens();
            }
        } else reqTokens();

        synchronized (waitLock) {
            waitLock.wait();
        }

        if (accessToken == null) return;

        synchronized (waitLock) {
            reqXBLToken();
            waitLock.wait();
        }

        if (xblToken == null) return;

        synchronized (waitLock) {
            reqXSTSToken();
            waitLock.wait();
        }

        if (xstsToken == null) return;

        synchronized (waitLock) {
            reqMinecraftToken();
            waitLock.wait();
        }

        if (mcToken == null) refreshToken = null; // It's invalid.
        else if (reqProfile()) saveData(storeRefreshToken);
    }

    /**
     * Acquires a device code and asks the user to authenticate
     * with it. Then gets the access token and refresh token from
     * Microsoft once the user has authenticated.
     * @throws IOException If anything goes wrong with the request.
     */
    private static void reqTokens() throws IOException {
        doRequest("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                String.format("client_id=%s&scope=%s", URLEncoder.encode(CLIENT_ID, "UTF-8"), URLEncoder.encode("XboxLive.signin offline_access", "UTF-8")),
                ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded"), (con, resp) -> {
                    JsonObject respObj = new Gson().fromJson(resp, JsonObject.class);

                    deviceCode = respObj.get("device_code").getAsString();
                    String verificationUri = respObj.get("verification_uri").getAsString();
                    String userCode = respObj.get("user_code").getAsString();

                    mainDialog = showDialog("DevLogin MSA Authentication", String.format("Please visit <a href=\"%s\">%s</a> and enter code <b>%s</b>.",
                            verificationUri, verificationUri, userCode), () -> isCancelled = true);

                    int interval = respObj.get("interval").getAsInt();
                    long expires = System.currentTimeMillis() + respObj.get("expires_in").getAsInt() * 1000L;

                    try {
                        Thread.sleep(interval * 1000L);
                    } catch (InterruptedException ignored) {}

                    try {
                        reqTokens(interval, expires);
                    } catch (UnsupportedEncodingException ignored) {} // Impossible at this stage.
                }, e -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire a code to authenticate your Microsoft account with (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not acquire a code to authenticate your Microsoft account with", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                });
    }

    /**
     * Continuously polls every set interval (should be 5 seconds)
     * to see if the user has authenticated yet.
     * @param interval The interval the Microsoft API would like us to use (should be 5 seconds).
     * @param expires Epoch when the device code expires.
     * @throws UnsupportedEncodingException Should not be possible.
     */
    private static void reqTokens(int interval, long expires) throws UnsupportedEncodingException {
        doRequest("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                String.format("grant_type=urn:ietf:params:oauth:grant-type:device_code&scope=%s&client_id=%s&device_code=%s", URLEncoder.encode("XboxLive.signin offline_access", "UTF-8"),
                        URLEncoder.encode(CLIENT_ID, "UTF-8"), URLEncoder.encode(deviceCode, "UTF-8")), ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded"),
                (con1, resp1) -> {
                    JsonObject resp1Obj = new Gson().fromJson(resp1, JsonObject.class);
                    if (resp1Obj.has("error")) {
                        if ("authorization_pending".equals(resp1Obj.get("error").getAsString()) && System.currentTimeMillis() < expires && !isCancelled)
                            try {
                                Thread.sleep(interval * 1000L);
                                reqTokens(interval, expires);
                                return;
                            } catch (InterruptedException | UnsupportedEncodingException ignored) {}
                    } else {
                        if (mainDialog != null) mainDialog.dispose();
                        else LOG.info("Authentication complete, requesting tokens...");
                        accessToken = resp1Obj.get("access_token").getAsString();
                        refreshToken = resp1Obj.get("refresh_token").getAsString();
                    }

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                }, (e) -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire a token to authenticate your Microsoft account with (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not acquire a token to authenticate your Microsoft account with", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                });
    }

    /**
     * Acquires a new access token using the stored refresh token.
     * @param successConsumer Gets called with either {@code true} or {@code false}
     *                        when finished indicating whether a new access token was acquired.
     * @throws IOException If anything goes wrong with the request.
     */
    private static void refreshToken(BooleanConsumer successConsumer) throws IOException {
        doRequest("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                String.format("grant_type=refresh_token&scope=%s&client_id=%s&refresh_token=%s", URLEncoder.encode("XboxLive.signin offline_access", "UTF-8"),
                        URLEncoder.encode(CLIENT_ID, "UTF-8"), URLEncoder.encode(refreshToken, "UTF-8")), ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded"),
                (con1, resp1) -> {
                    JsonObject resp1Obj = new Gson().fromJson(resp1, JsonObject.class);
                    if (!resp1Obj.has("error")) {
                        accessToken = resp1Obj.get("access_token").getAsString();
                        MSA.refreshToken = resp1Obj.get("refresh_token").getAsString();
                    }

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                    successConsumer.accept(true);
                }, (e) -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire a token to authenticate your Microsoft account with (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not refresh token", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                    successConsumer.accept(false);
                });
    }

    /**
     * Requests the XBL token from Xbox Live using the Microsoft access token.
     */
    private static void reqXBLToken() {
        String body = " {\n" +
                "    \"Properties\": {\n" +
                "        \"AuthMethod\": \"RPS\",\n" +
                "        \"SiteName\": \"user.auth.xboxlive.com\",\n" +
                "        \"RpsTicket\": \"d=" + accessToken + "\"" +
                "    },\n" +
                "    \"RelyingParty\": \"http://auth.xboxlive.com\",\n" +
                "    \"TokenType\": \"JWT\"\n" +
                " }";
        doRequest("POST", "https://user.auth.xboxlive.com/user/authenticate", body, ImmutableMap.of("Content-Type", "application/json", "Accept", "application/json"),
                (con, resp) -> {
                    JsonObject respObj = new Gson().fromJson(resp, JsonObject.class);
                    xblToken = respObj.get("Token").getAsString();
                    userHash = respObj
                            .get("DisplayClaims").getAsJsonObject()
                            .get("xui").getAsJsonArray()
                            .get(0).getAsJsonObject()
                            .get("uhs").getAsString();

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                }, e -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire XBL token (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not acquire XBL token", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                });
    }

    /**
     * Requests the XSTS token from Xbox Live using the XBL token.
     */
    private static void reqXSTSToken() {
        String body = " {\n" +
                "    \"Properties\": {\n" +
                "        \"SandboxId\": \"RETAIL\",\n" +
                "        \"UserTokens\": [\n" +
                "            \"" + xblToken + "\"" +
                "        ]\n" +
                "    },\n" +
                "    \"RelyingParty\": \"rp://api.minecraftservices.com/\",\n" +
                "    \"TokenType\": \"JWT\"\n" +
                " }";
        doRequest("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", body, ImmutableMap.of("Content-Type", "application/json", "Accept", "application/json"),
                (con, resp) -> {
                    JsonObject respObject = new Gson().fromJson(resp, JsonObject.class);
                    //respObject.addProperty("XErr", 2148916238L);
                    if (respObject.has("XErr"))
                        showDialog("DevLogin MSA Authentication - error", "Could not acquire XSTS token<br>" +
                                "Error code: " + respObject.get("XErr") + ", message: " + respObject.get("Message") + ", redirect: " +
                                (respObject.has("Redirect") ? "<a href=\"" + respObject.get("Redirect") + "\">" +
                                        respObject.get("Redirect") + "</a>" : "null") + "<br>" +
                                "Have a look <a href=\"https://wiki.vg/Microsoft_Authentication_Scheme#Authenticate_with_XSTS\">here</a> " +
                                "for a short list of known error codes.");
                    else xstsToken = respObject.get("Token").getAsString();

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                }, e -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire XSTS token (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not acquire XSTS token", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                });
    }

    /**
     * Requests the Minecraft token from Minecraft Services using the XSTS token.
     */
    private static void reqMinecraftToken() {
        String body = "{\"identityToken\": \"XBL3.0 x=" + userHash + ";" + xstsToken + "\"}";
        doRequest("POST", "https://api.minecraftservices.com/authentication/login_with_xbox", body,
                ImmutableMap.of("Content-Type", "application/json", "Accept", "application/json"), (con, resp) -> {
                    JsonObject respObject = new Gson().fromJson(resp, JsonObject.class);
                    if (respObject.has("error") && "UnauthorizedOperationException".equals(respObject.get("error").getAsString())) mcToken = null;
                    else mcToken = respObject.get("access_token").getAsString();

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                }, e -> {
                    showDialog("DevLogin MSA Authentication - error", "Could not acquire Minecraft token (" + e.getClass().getSimpleName() + ").");
                    LOG.error("Could not acquire Minecraft token", e);

                    synchronized (waitLock) {
                        waitLock.notify();
                    }
                });
    }

    /**
     * Requests the profile from Minecraft Services using the Minecraft token.
     * Required to login as logging in required the username and uuid of the player.
     * Also used to check if the token is valid.
     *
     * @return Whether the account associated with this token owns Minecraft.
     */
    public static boolean reqProfile() {
        if (profile != null) return true;

        Object waitLock = new Object();
        AtomicBoolean ownsMc = new AtomicBoolean();

        doRequest("GET", "https://api.minecraftservices.com/minecraft/profile", null, ImmutableMap.of("Authorization", "Bearer " + mcToken), (con, resp) -> {
            JsonObject respObj = new Gson().fromJson(resp, JsonObject.class);
            ownsMc.set(respObj != null && !respObj.has("error"));

            if (ownsMc.get() && respObj != null) // Null-check to get rid of warning.
                profile = new MinecraftProfile(
                        respObj.get("name").getAsString(),
                        UUIDTypeAdapter.fromString(respObj.get("id").getAsString()),
                        mcToken
                );

            synchronized (waitLock) {
                waitLock.notify();
            }
        }, LOG::catching);

        synchronized (waitLock) {
            try {
                waitLock.wait();
            } catch (InterruptedException e) {
                LOG.catching(e);
            }
        }

        return ownsMc.get();
    }

    // Access methods

    /**
     * @return Whether we have successfully logged in.
     */
    public static boolean isLoggedIn() {
        return mcToken != null;
    }

    /**
     * @return The Minecraft profile associated with the account used to login.
     */
    public static MinecraftProfile getProfile() {
        return profile;
    }

    /**
     * Deletes all stored tokens once as they could pose a security threat.
     * Should be called after the entire process is finished.
     */
    public static void cleanup() {
        deviceCode = null;
        accessToken = null;
        refreshToken = null;
        xblToken = null;
        userHash = null;
        xstsToken = null;
        mcToken = null;
        profile = null;
    }

    // Utility methods

    /**
     * Performs an HTTP request.
     * @param method The method this HTTP request uses. E.g. GET, POST, DELETE, etc.
     * @param urlStr The URL to make this request to.
     * @param body The body of the request. Used for most request methods except GET.
     * @param headers The headers to attach to this request. E.g. Content-Type or User-Agent.
     * @param responseConsumer The consumer called on a successful response. Gets the connection used and the plain-text response.
     * @param exceptionConsumer The consumer called when an error occurs. Gets the exception that was thrown.
     */
    public static void doRequest(String method, String urlStr, String body, Map<String, String> headers, BiConsumer<HttpURLConnection, String> responseConsumer, Consumer<Exception> exceptionConsumer) {
        TPE.execute(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection con = (HttpURLConnection) url.openConnection(proxy);
                con.setRequestMethod(method);
                if (body != null) con.setDoOutput(true);
                if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) con.setRequestProperty(entry.getKey(), entry.getValue());
                if (body != null)
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(con.getOutputStream()))) {
                        writer.write(body);
                    }
                StringBuilder sb = new StringBuilder();
                InputStream stream;
                try {
                    stream = con.getInputStream();
                } catch (IOException e) {
                    stream = con.getErrorStream();
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                }
                responseConsumer.accept(con, sb.toString());
            } catch (IOException e) {
                exceptionConsumer.accept(e);
            }
        });
    }

    /**
     * Shows a basic Swing dialog with a title and a message.
     * @param title The title of the dialog.
     * @param message The message this dialog should contain.
     */
    public static void showDialog(String title, String message) {
        showDialog(title, message, null);
    }

    /**
     * Shows a basic Swing dialog with a title and a message.
     * @param title The title of the dialog.
     * @param message The message this dialog should contain.
     * @param onDispose The runnable called when the dialog is disposed (closed).
     */
    public static JFrame showDialog(String title, String message, Runnable onDispose) {
        if (noDialog) {
            LOG.info("\n-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-\n" +
                    tagPattern.matcher(urlPattern.matcher(message).replaceAll("$1")).replaceAll("$2").replace("<br>", "\n") +
                    "\n-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");

            return null;
        }

        if (!Minecraft.ON_OSX) // Calls to setLookAndFeel on Mac appear to freeze the game.
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
                LOG.error("Could not set system look and feel.", e);
            }

        JFrame frame = new JFrame(title);
        frame.setLayout(new GridBagLayout());

        JEditorPane textPane = new JEditorPane();
        textPane.setContentType("text/html");
        textPane.setText("<html>" + message + "</html>");
        textPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED)
                try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (IOException | URISyntaxException ex) {
                    LOG.error("Error while trying to browse to " + e.getURL(), ex);
                }
        });
        textPane.setEditable(false);
        textPane.setOpaque(false);
        frame.add(textPane);

        frame.pack();
        frame.setSize(frame.getWidth() + 20, frame.getHeight() + 50);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (onDispose != null) onDispose.run();
            }
        });
        frame.setVisible(true);

        return frame;
    }

    /**
     * Stores some of the tokens that are required to login.
     * @param storeRefreshToken Whether the refresh token should be stored.
     */
    private static void saveData(boolean storeRefreshToken) {
        Map<String, String> data = new HashMap<>();
        data.put("refreshToken", storeRefreshToken ? refreshToken : null);
        data.put("mcToken", mcToken);

        try (PrintWriter writer = new PrintWriter(tokenFile, "UTF-8")) {
            writer.print(new GsonBuilder().setPrettyPrinting().create().toJson(data));
            writer.flush();
        } catch (IOException e) {
            LOG.error("Could not save token data.", e);
        }
    }

    /**
     * Reads data that was potentially saved before.
     * @return The data that was saved before or null if an error occurs or there is no stored data.
     */
    @SuppressWarnings("UnstableApiUsage")
    private static Map<String, String> readData() {
        try {
            return tokenFile.exists() ? new Gson().fromJson(String.join("\n", Files.readAllLines(tokenFile.toPath())), new TypeToken<Map<String, String>>() {}.getType()) : null;
        } catch (IOException e) {
            LOG.error("Could not read token data.", e);
            return null;
        }
    }

    /**
     * A simple Minecraft profile. Contains all the necessities to login.
     */
    public static class MinecraftProfile {
        private final String name, token;
        private final UUID uuid;

        private MinecraftProfile(String name, UUID uuid, String token) {
            this.name = name;
            this.uuid = uuid;
            this.token = token;
        }

        /**
         * @return The username of this profile. E.g. PlanetTeamSpeak
         */
        public String getName() {
            return name;
        }

        /**
         * @return The UUID of this profile. E.g. 1aa35f31-0881-4959-bd14-21e8a72ba0c1
         */
        public UUID getUuid() {
            return uuid;
        }

        /**
         * @return The authentication token to login. E.g. eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQyNjUzMTQ4O...
         */
        public String getToken() {
            return token;
        }
    }
}
