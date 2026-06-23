package com.ptsmods.devlogin.http;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * {@link HttpClient} backed by {@code java.net.http.HttpClient}, the modern,
 * built-in HTTP client added in Java 11.
 * <p>
 * This class lives in its own {@code modern} source set (see {@code common/build.gradle})
 * which is compiled at a higher Java release than the rest of the mod, so it can
 * use the {@code java.net.http} API directly instead of through reflection. Because
 * of that higher release the class only loads on Java 17+; on older JVMs loading it
 * throws an {@link UnsupportedClassVersionError}, which {@link HttpClients} treats
 * as this implementation being unavailable and falls back to the {@link LegacyJavaHttpClient}.
 */
public class ModernJavaHttpClient implements HttpClient {
    @Override
    public String execute(String method, String url, String body, Map<String, String> headers, Proxy proxy, int timeout) throws Exception {
        Duration duration = Duration.ofMillis(timeout);

        // java.net.http.HttpClient is fully qualified to avoid clashing with our own HttpClient interface.
        java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(duration);
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT && proxy.address() instanceof InetSocketAddress)
            clientBuilder.proxy(ProxySelector.of((InetSocketAddress) proxy.address()));
        java.net.http.HttpClient client = clientBuilder.build();

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(duration)
                .method(method, publisher);
        if (headers != null) headers.forEach(requestBuilder::header);

        HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
