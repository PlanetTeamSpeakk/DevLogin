package com.ptsmods.devlogin.http;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Response;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Map;

/**
 * {@link HttpClient} backed by the <a href="https://github.com/AsyncHttpClient/async-http-client">async-http-client</a>
 * library. This is the preferred implementation, but the library is not always
 * present on the classpath, in which case referencing any of the classes in this
 * file throws a {@link NoClassDefFoundError} and {@link HttpClients} falls back
 * to one of the built-in implementations.
 */
public class AsyncHttpClientImpl implements HttpClient {
    @Override
    public String execute(String method, String url, String body, Map<String, String> headers, Proxy proxy, int timeout) throws Exception {
        DefaultAsyncHttpClientConfig.Builder config = new DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(Duration.ofMillis(timeout))
                .setReadTimeout(Duration.ofMillis(timeout));

        // Turn the Proxy into a ProxyServer.
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT && proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            ProxyType type = proxy.type() == Proxy.Type.SOCKS ? ProxyType.SOCKS_V5 : ProxyType.HTTP;
            config.setProxyServer(new ProxyServer.Builder(address.getHostName(), address.getPort())
                    .setProxyType(type)
                    .build());
        }

        try (AsyncHttpClient client = Dsl.asyncHttpClient(config)) {
            BoundRequestBuilder request = client.prepare(method, url);
            if (headers != null) headers.forEach(request::setHeader);
            if (body != null) request.setBody(body);

            Response response = request.execute().get();
            return response.getResponseBody();
        }
    }
}
