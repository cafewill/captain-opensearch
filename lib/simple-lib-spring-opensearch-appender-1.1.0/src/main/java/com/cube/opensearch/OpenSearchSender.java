package com.cube.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class OpenSearchSender {
    private static final HostnameVerifier NOOP_VERIFIER = (hostname, session) -> true;

    private final ObjectMapper objectMapper;
    private final String url;
    private final String basicAuth;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final SSLSocketFactory sslSocketFactory;

    OpenSearchSender(ObjectMapper objectMapper,
                     String url,
                     String username,
                     String password,
                     int connectTimeoutMillis,
                     int readTimeoutMillis,
                     boolean trustAllSsl) {
        this.objectMapper = objectMapper;
        this.url = url;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.basicAuth = (username == null || username.isBlank()) ? null : Base64.getEncoder()
                .encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.sslSocketFactory = trustAllSsl ? buildTrustAllFactory() : null;
    }

    SendResult send(List<BulkPayloadBuilder.BulkItem> items, String payload, boolean retryPartialFailures) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = openConnection(target);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            if (basicAuth != null) {
                connection.setRequestProperty("Authorization", "Basic " + basicAuth);
            }
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int status = connection.getResponseCode();
            try (InputStream is = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                String response = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if (status >= 500) {
                    return SendResult.retryable(items, "server error status=" + status + " body=" + abbreviate(response), null);
                }
                if (status >= 400) {
                    return SendResult.fatal("client error status=" + status + " body=" + abbreviate(response), null);
                }
                return analyzeBulkResponse(items, response, retryPartialFailures);
            }
        } catch (Exception ex) {
            return SendResult.retryable(items, "transport error=" + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String resolveInstanceId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "pid-" + ProcessHandle.current().pid();
        }
    }

    private HttpURLConnection openConnection(URL target) throws Exception {
        if ("https".equalsIgnoreCase(target.getProtocol())) {
            HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
            if (sslSocketFactory != null) {
                connection.setSSLSocketFactory(sslSocketFactory);
                connection.setHostnameVerifier(NOOP_VERIFIER);
            }
            return connection;
        }
        return (HttpURLConnection) target.openConnection();
    }

    private SendResult analyzeBulkResponse(List<BulkPayloadBuilder.BulkItem> items,
                                           String response,
                                           boolean retryPartialFailures) throws Exception {
        if (response == null || response.isBlank()) {
            return SendResult.success();
        }
        JsonNode root = objectMapper.readTree(response);
        JsonNode errorsNode = root.get("errors");
        if (errorsNode == null || !errorsNode.asBoolean(false)) {
            return SendResult.success();
        }
        if (!retryPartialFailures) {
            return SendResult.fatal("bulk response contains errors body=" + abbreviate(response), null);
        }

        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return SendResult.retryable(items, "bulk response contains errors but items are missing body=" + abbreviate(response), null);
        }

        List<BulkPayloadBuilder.BulkItem> retryable = new ArrayList<>();
        for (int i = 0; i < itemsNode.size() && i < items.size(); i++) {
            JsonNode opNode = itemsNode.get(i);
            JsonNode resultNode = opNode.elements().hasNext() ? opNode.elements().next() : null;
            if (resultNode == null) {
                retryable.add(items.get(i));
                continue;
            }
            JsonNode statusNode = resultNode.get("status");
            int status = statusNode == null ? 500 : statusNode.asInt(500);
            if (status >= 500 || status == 429) {
                retryable.add(items.get(i));
            }
        }
        if (retryable.isEmpty()) {
            return SendResult.fatal("bulk response contains non-retryable item errors body=" + abbreviate(response), null);
        }
        return SendResult.retryable(retryable,
                "bulk response contains retryable partial failures count=" + retryable.size() + " body=" + abbreviate(response),
                null);
    }

    private SSLSocketFactory buildTrustAllFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize trust-all SSL factory", e);
        }
    }

    private String abbreviate(String value) {
        return value == null ? "" : value.substring(0, Math.min(500, value.length()));
    }
}
