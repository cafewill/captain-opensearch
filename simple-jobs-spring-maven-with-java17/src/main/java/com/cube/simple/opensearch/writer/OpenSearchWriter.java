package com.cube.simple.opensearch.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpHeaders;

import com.cube.simple.opensearch.config.HttpRequestHeader;
import com.cube.simple.opensearch.config.HttpRequestHeaders;
import com.cube.simple.opensearch.config.Settings;
import com.cube.simple.opensearch.util.ErrorReporter;

public class OpenSearchWriter implements SafeWriter {

    private static final HostnameVerifier NOOP_VERIFIER = (hostname, session) -> true;

    private StringBuilder sendBuffer;

    private ErrorReporter errorReporter;
    private Settings settings;
    private Collection<HttpRequestHeader> headerList;
    private SSLSocketFactory sslSocketFactory;

    private boolean bufferExceeded;
    private boolean compressedTransfer;

    public OpenSearchWriter(ErrorReporter errorReporter, Settings settings, HttpRequestHeaders headers) {
        this.errorReporter = errorReporter;
        this.settings = settings;
        this.headerList = headers != null && headers.getHeaders() != null
                ? headers.getHeaders()
                : Collections.emptyList();

        this.sslSocketFactory = settings.isTrustAllSsl() ? buildTrustAllFactory() : null;
        this.sendBuffer = new StringBuilder();
        compressedTransfer = false;
        for (HttpRequestHeader header : this.headerList) {
            if (header.getName().toLowerCase().equals(HttpHeaders.CONTENT_ENCODING.toLowerCase()) && header.getValue().equals("gzip")) {
                compressedTransfer = true;
                break;
            }
        }
    }

    public void write(char[] cbuf, int off, int len) {
        if (bufferExceeded) {
            return;
        }

        sendBuffer.append(cbuf, off, len);

        if (sendBuffer.length() >= settings.getMaxQueueSize()) {
            errorReporter.logWarning("Send queue maximum size exceeded - log messages will be lost until the buffer is cleared");
            bufferExceeded = true;
        }
    }

    public void sendData() throws IOException {
        if (sendBuffer.length() <= 0) {
            return;
        }

        HttpURLConnection urlConnection = null;
        try {
            urlConnection = (HttpURLConnection) (settings.getUrl().openConnection());
            if (sslSocketFactory != null && urlConnection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) urlConnection;
                https.setSSLSocketFactory(sslSocketFactory);
                https.setHostnameVerifier(NOOP_VERIFIER);
            }
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);
            urlConnection.setReadTimeout(settings.getReadTimeout());
            urlConnection.setConnectTimeout(settings.getConnectTimeout());
            urlConnection.setRequestMethod("POST");

            String body = sendBuffer.toString();

            if (!headerList.isEmpty()) {
                for (HttpRequestHeader header : headerList) {
                    urlConnection.setRequestProperty(header.getName(), header.getValue());
                }
            }

            if (settings.getAuthentication() != null) {
                settings.getAuthentication().addAuth(urlConnection, body);
            }

            writeData(urlConnection, body);

            int rc = urlConnection.getResponseCode();

            if (rc == 200) {
                // Consume response stream to enable connection reuse (keep-alive)
                // HttpURLConnection requires full response consumption before reuse
                try (InputStream is = urlConnection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    while (is.read(buffer) != -1) {
                        // Consume response data
                    }
                }
            } else {
                String data = slurpErrors(urlConnection); 
                if (rc >= 400 && rc < 500) {
                    errorReporter.logInfo("Send queue cleared - drop log messages due to http 4xx error.");
                    sendBuffer.setLength(0);
                    bufferExceeded = false;
                }
                throw new IOException("Got response code [" + rc + "] from server with data " + data);
            }

            sendBuffer.setLength(0);
            if (bufferExceeded) {
                errorReporter.logInfo("Send queue cleared - log messages will no longer be lost");
                bufferExceeded = false;
            }
        } finally {
            // Disconnect releases this HttpURLConnection instance. When response streams were fully
            // consumed (as above), most JDKs retain the underlying socket in the keep-alive cache
            // for reuse; disconnect() does not necessarily close the TCP connection.
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    public boolean hasPendingData() {
        return sendBuffer.length() != 0;
    }

    protected String slurpErrors(HttpURLConnection urlConnection) {
        try (InputStream stream = urlConnection.getErrorStream()) {
            if (stream == null) {
                return "<no data>";
            }

            StringBuilder builder = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(stream, "UTF-8")) {
                char[] buf = new char[2048];
                int numRead;
                while ((numRead = reader.read(buf)) > 0) {
                    builder.append(buf, 0, numRead);
                }
            }
            return builder.toString();
        } catch (Exception e) {
            return "<error retrieving data: " + e.getMessage() + ">";
        }
    }

    private void writeData(HttpURLConnection urlConnection, String body) throws IOException {
        if (this.compressedTransfer) {
            try (OutputStream out = urlConnection.getOutputStream();
                 Writer writer = new OutputStreamWriter(new GZIPOutputStream(out), "UTF-8")) {
                writer.write(body);
                writer.flush();
            }
        } else {
            try (OutputStream out = urlConnection.getOutputStream();
                 Writer writer = new OutputStreamWriter(out, "UTF-8")) {
                writer.write(body);
                writer.flush();
            }
        }
    }

    public StringBuilder getSendBuffer() {
        return sendBuffer;
    }

    public Settings getSettings() {
        return settings;
    }

    public Collection<HttpRequestHeader> getHeaderList() {
        return headerList;
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }

    public boolean isBufferExceeded() {
        return bufferExceeded;
    }

    public void setBufferExceeded(boolean bufferExceeded) {
        this.bufferExceeded = bufferExceeded;
    }

    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build trust-all SSL factory", e);
        }
    }

}
