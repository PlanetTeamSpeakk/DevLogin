package com.ptsmods.devlogin.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Picks a working {@link HttpClient} implementation and performs requests with it.
 * <p>
 * The implementations are tried in the following order:
 * <ol>
 *     <li>{@link AsyncHttpClientImpl} &ndash; the async-http-client library (when present on the classpath);</li>
 *     <li>{@code ModernJavaHttpClient} &ndash; {@code java.net.http.HttpClient}, built in since Java 11;</li>
 *     <li>{@link LegacyJavaHttpClient} &ndash; {@link java.net.HttpURLConnection}, available on every JVM.</li>
 * </ol>
 * Whenever an implementation turns out to be unavailable (typically a
 * {@link NoClassDefFoundError} because the async library is missing, or an
 * {@link UnsupportedClassVersionError} on a JVM too old for the modern client) the next one is tried.
 * The first implementation that successfully performs a request is remembered and
 * reused for all subsequent requests.
 */
public final class HttpClients {
    private static final Logger LOG = LogManager.getLogger("DevLogin-HTTP");
    private static final List<Supplier<HttpClient>> FACTORIES = Arrays.asList(
            AsyncHttpClientImpl::new,
            HttpClients::newModernClient,
            LegacyJavaHttpClient::new);
    private static HttpClient client;

    private HttpClients() {}

    /**
     * Creates the {@code ModernJavaHttpClient}, which is compiled in a separate
     * source set against the Java 11+ API and is therefore not on this module's
     * compile classpath; it is loaded by name instead. On a JVM that cannot load
     * it (older than the release it was compiled for) this throws, which
     * {@link #execute} treats as the implementation being unavailable.
     */
    private static HttpClient newModernClient() {
        try {
            return Class.forName("com.ptsmods.devlogin.http.ModernJavaHttpClient")
                    .asSubclass(HttpClient.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("The modern Java HTTP client is unavailable.", e);
        }
    }

    /**
     * Performs a single HTTP request using the first available implementation.
     *
     * @param method  The request method, e.g. {@code GET} or {@code POST}.
     * @param url     The URL to send the request to.
     * @param body    The request body, or {@code null} when there is none.
     * @param headers The headers to attach to the request, or {@code null} for none.
     * @param proxy   The proxy to route the request through, or {@link Proxy#NO_PROXY} for none.
     * @param timeout The connect/read timeout in milliseconds.
     * @return The plain-text response body.
     * @throws Exception If the request itself fails, or if no usable implementation could be found.
     */
    public static String execute(String method, String url, String body, Map<String, String> headers, Proxy proxy, int timeout) throws Exception {
        // Once an implementation has proven to work, keep using it.
        if (client != null) return client.execute(method, url, body, headers, proxy, timeout);

        Throwable lastError = null;
        for (Supplier<HttpClient> factory : FACTORIES) {
            HttpClient candidate;
            try {
                candidate = factory.get();
            } catch (Throwable t) {
                // Instantiating the implementation already failed (e.g. a missing class).
                lastError = t;
                LOG.debug("Could not instantiate an HTTP client implementation, trying the next one.");
                continue;
            }

            try {
                String response = candidate.execute(method, url, body, headers, proxy, timeout);
                rememberClient(candidate);
                return response;
            } catch (Throwable t) {
                if (isUnavailable(t)) {
                    lastError = t;
                    LOG.debug("HTTP client {} is unavailable, trying the next one.", candidate.getClass().getSimpleName());
                    continue;
                }

                // The implementation works; the request itself failed. Keep this
                // implementation and report the failure to the caller.
                rememberClient(candidate);
                if (t instanceof Error) throw (Error) t;
                throw (Exception) t;
            }
        }

        throw new IllegalStateException("No usable HTTP client implementation could be found.", lastError);
    }

    private static void rememberClient(HttpClient candidate) {
        if (client == null) {
            client = candidate;
            LOG.info("Using {} for HTTP requests.", candidate.getClass().getSimpleName());
        }
    }

    /**
     * Determines whether the given throwable means the implementation that threw
     * it is unavailable (as opposed to the request having genuinely failed). The
     * whole cause chain is inspected because the error may be wrapped, e.g. in an
     * {@link java.util.concurrent.ExecutionException}.
     */
    private static boolean isUnavailable(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause())
            if (cause instanceof LinkageError || cause instanceof ReflectiveOperationException) return true;
        return false;
    }
}
