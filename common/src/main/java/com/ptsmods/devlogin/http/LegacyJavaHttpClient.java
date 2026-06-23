package com.ptsmods.devlogin.http;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;

/**
 * {@link HttpClient} backed by {@link HttpURLConnection}, the way HTTP requests
 * have been made since Java 1.1. This is the last-resort implementation: it has
 * no external dependencies and is available on every JVM the mod can run on, so
 * it always works when the others are unavailable.
 */
public class LegacyJavaHttpClient implements HttpClient {
    @SuppressWarnings("CharsetObjectCanBeUsed") // no (Java 8 compat)
    @Override
    public String execute(String method, String url, String body, Map<String, String> headers, Proxy proxy, int timeout) throws IOException {
        boolean useProxy = proxy != null && proxy.type() != Proxy.Type.DIRECT;
        URL parsedUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) (useProxy ? parsedUrl.openConnection(proxy) : parsedUrl.openConnection());
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestMethod(method);
        if (headers != null) headers.forEach(conn::setRequestProperty);

        // Write body if there is one
        if (body != null) {
            conn.setDoOutput(true);
            try (BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                out.write(body.getBytes("UTF-8"));
                out.flush();
            }
        }

        // Read response
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            // getErrorStream() returns null if no connection has been made yet.
            is = conn.getErrorStream();
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null)
            response.append(inputLine);
        in.close();

        return response.toString();
    }
}
