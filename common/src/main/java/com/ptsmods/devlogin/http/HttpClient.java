package com.ptsmods.devlogin.http;

import java.net.Proxy;
import java.util.Map;

/**
 * A minimal abstraction over the various ways an HTTP request can be made.
 * <p>
 * Several implementations exist so the mod keeps working even when one of them
 * cannot be loaded (most commonly because the async HTTP client library was not
 * added to the classpath, causing a {@link NoClassDefFoundError}).
 * {@link HttpClients} tries them in order and falls back to the next one
 * whenever the current implementation turns out to be unavailable.
 *
 * @see HttpClients
 */
public interface HttpClient {
    /**
     * Performs a single, blocking HTTP request.
     *
     * @param method  The request method, e.g. {@code GET} or {@code POST}.
     * @param url     The URL to send the request to.
     * @param body    The request body, or {@code null} when there is none (e.g. for most GET requests).
     * @param headers The headers to attach to the request, or {@code null} for none.
     * @param proxy   The proxy to route the request through. {@link Proxy#NO_PROXY} (or a {@code DIRECT} proxy) means no proxy.
     * @param timeout The connect/read timeout in milliseconds.
     * @return The plain-text response body.
     * @throws Exception If the request fails. A {@link LinkageError} or
     *                   {@link ReflectiveOperationException} signals that this
     *                   implementation is unavailable and the next one should be tried;
     *                   any other exception is treated as a genuine request failure.
     */
    String execute(String method, String url, String body, Map<String, String> headers, Proxy proxy, int timeout) throws Exception;
}
