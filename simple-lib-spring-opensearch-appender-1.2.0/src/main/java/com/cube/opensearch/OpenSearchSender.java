package com.cube.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
        this.url = trimTrailingSlash(url);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.basicAuth = buildBasicAuth(username, password);
        this.sslSocketFactory = trustAllSsl ? buildTrustAllFactory() : null;
    }

    SendResult send(List<BulkPayloadBuilder.BulkItem> items, String payload, boolean retryPartialFailures) {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(resolveBulkUrl());
            connection = openConnection(endpoint);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-ndjson");
            connection.setRequestProperty("Accept", "application/json");
            if (basicAuth != null) {
                connection.setRequestProperty("Authorization", basicAuth);
            }
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            String body = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 500 || code == 429) {
                return SendResult.retryable(items, "HTTP " + code + ": " + abbreviate(body), null);
            }
            if (code >= 400) {
                return SendResult.fatal("HTTP " + code + ": " + abbreviate(body), null);
            }
            return analyzeBulkResponse(items, body, retryPartialFailures);
        } catch (Exception e) {
            return SendResult.retryable(items, "send exception", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String resolveInstanceId() {
        String fromEnv = firstNonBlank(System.getenv("INSTANCE_ID"), System.getenv("HOSTNAME"));
        if (fromEnv != null) {
            return fromEnv;
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ignored) {
        }
        try {
            String pidName = ManagementFactory.getRuntimeMXBean().getName();
            if (pidName != null && !pidName.isBlank()) {
                return "pid-" + pidName.split("@")[0];
            }
        } catch (Exception ignored) {
        }
        return "unknown-instance";
    }

    private HttpURLConnection openConnection(URL endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);

        if (connection instanceof HttpsURLConnection httpsURLConnection && sslSocketFactory != null) {
            httpsURLConnection.setSSLSocketFactory(sslSocketFactory);
            httpsURLConnection.setHostnameVerifier(NOOP_VERIFIER);
        }
        return connection;
    }

    private SendResult analyzeBulkResponse(List<BulkPayloadBuilder.BulkItem> items,
                                           String body,
                                           boolean retryPartialFailures) throws Exception {
        if (body == null || body.isBlank()) {
            return SendResult.success();
        }

        JsonNode root = objectMapper.readTree(body);
        boolean errors = root.path("errors").asBoolean(false);
        if (!errors) {
            return SendResult.success();
        }

        ArrayNode responseItems = (ArrayNode) root.path("items");
        List<BulkPayloadBuilder.BulkItem> retryable = new ArrayList<>();
        StringBuilder fatalMessages = new StringBuilder();

        for (int i = 0; i < responseItems.size() && i < items.size(); i++) {
            JsonNode node = responseItems.get(i).path("index");
            int status = node.path("status").asInt(200);
            if (status == 429 || status >= 500) {
                retryable.add(items.get(i));
            } else if (status >= 400) {
                JsonNode error = node.path("error");
                fatalMessages.append("[")
                        .append(status)
                        .append("] ")
                        .append(error.path("type").asText("unknown"))
                        .append(": ")
                        .append(error.path("reason").asText("unknown"))
                        .append(' ');
            }
        }

        if (!retryable.isEmpty() && retryPartialFailures) {
            return SendResult.retryable(retryable, "partial retryable: " + fatalMessages, null);
        }
        if (!fatalMessages.isEmpty()) {
            return SendResult.fatal(fatalMessages.toString().trim(), null);
        }
        return SendResult.success();
    }

    private SSLSocketFactory buildTrustAllFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build trust-all SSL factory", e);
        }
    }

    private String readBody(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try (InputStream is = inputStream; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            is.transferTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String buildBasicAuth(String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String raw = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private String resolveBulkUrl() {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.endsWith("/_bulk") ? url : url + "/_bulk";
    }

    private static String trimTrailingSlash(String source) {
        if (source == null) {
            return null;
        }
        return source.endsWith("/") ? source.substring(0, source.length() - 1) : source;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
