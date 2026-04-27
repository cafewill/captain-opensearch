package com.cube.simple.opensearch;

import java.net.HttpURLConnection;

public interface OpenSearchAuthentication {
    void addAuth(HttpURLConnection urlConnection, String body);
}
