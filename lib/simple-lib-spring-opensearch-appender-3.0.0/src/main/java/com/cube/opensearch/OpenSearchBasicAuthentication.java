package com.cube.opensearch;

import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OpenSearchBasicAuthentication implements OpenSearchAuthentication {

    private volatile String cachedAuthHeader;
    private String username;
    private String password;

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public void addAuth(HttpURLConnection urlConnection, String body) {
        if (cachedAuthHeader == null) {
            cachedAuthHeader = buildAuthHeader(urlConnection);
        }
        if (cachedAuthHeader != null) {
            urlConnection.setRequestProperty("Authorization", cachedAuthHeader);
        }
    }

    private String buildAuthHeader(HttpURLConnection urlConnection) {
        String credentials;
        if (username != null && password != null) {
            credentials = username + ":" + password;
        } else {
            String userInfo = urlConnection.getURL().getUserInfo();
            if (userInfo == null) {
                return null;
            }
            try {
                credentials = URLDecoder.decode(userInfo, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
