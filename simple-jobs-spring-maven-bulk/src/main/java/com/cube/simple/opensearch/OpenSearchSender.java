package com.cube.simple.opensearch;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class OpenSearchSender {
    private static final HostnameVerifier NOOP_VERIFIER = (hostname, session) -> true;

    private final ObjectMapper objectMapper;
    private final String url;
    private final OpenSearchAuthentication authentication;
    private final int connectTimeout;
    private final int readTimeout;
    private final SSLSocketFactory sslSocketFactory;
    private final OpenSearchHeaders headers;

    OpenSearchSender(ObjectMapper objectMapper,
                     String url,
                     OpenSearchAuthentication authentication,
                     int connectTimeout,
                     int readTimeout,
                     boolean trustAllSsl,
                     OpenSearchHeaders headers) {
        this.objectMapper = objectMapper;
        this.url = trimTrailingSlash(url);
        this.authentication = authentication;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.sslSocketFactory = trustAllSsl ? buildTrustAllFactory() : null;
        this.headers = headers;
    }

    SendResult send(List<BulkPayloadBuilder.BulkItem> items, String payload) {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(resolveBulkUrl());
            connection = openConnection(endpoint);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-ndjson");
            connection.setRequestProperty("Accept", "application/json");
            if (authentication != null) {
                authentication.addAuth(connection, payload);
            }
            applyHeaders(connection);
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
            return analyzeBulkResponse(items, body);
        } catch (Exception e) {
            return SendResult.retryable(items, "send exception", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(URL endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);

        if (connection instanceof HttpsURLConnection httpsURLConnection && sslSocketFactory != null) {
            httpsURLConnection.setSSLSocketFactory(sslSocketFactory);
            httpsURLConnection.setHostnameVerifier(NOOP_VERIFIER);
        }
        return connection;
    }

    private SendResult analyzeBulkResponse(List<BulkPayloadBuilder.BulkItem> items, String body) throws Exception {
        if (body == null || body.trim().isEmpty()) {
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
            JsonNode itemNode = responseItems.get(i);
            JsonNode node = itemNode.path("index");
            if (node.isMissingNode()) node = itemNode.path("create");
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

        if (!retryable.isEmpty()) {
            return SendResult.retryable(retryable, "partial retryable: " + fatalMessages, null);
        }
        if (!fatalMessages.isEmpty()) {
            return SendResult.fatal(fatalMessages.toString().trim(), null);
        }
        return SendResult.success();
    }

    private void applyHeaders(HttpURLConnection connection) {
        if (headers == null || headers.getHeaders().isEmpty()) {
            return;
        }
        for (OpenSearchHeader header : headers.getHeaders()) {
            if (header == null || header.getName() == null || header.getName().trim().isEmpty()) {
                continue;
            }
            connection.setRequestProperty(header.getName(), header.getValue() == null ? "" : header.getValue());
        }
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

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private String resolveBulkUrl() {
        if (url == null || url.trim().isEmpty()) {
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
}
