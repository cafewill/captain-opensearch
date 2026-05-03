package com.cube.simple.opensearch;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.cube.simple.opensearch.config.Settings;
import com.cube.simple.opensearch.util.ErrorReporter;
import com.cube.simple.opensearch.writer.SafeWriter;

public class OpenSearchOutputAggregator extends Writer {

    private Settings settings;
    private ErrorReporter errorReporter;
    private List<SafeWriter> writers;

    public OpenSearchOutputAggregator(Settings settings, ErrorReporter errorReporter) {
        this.writers = new ArrayList<SafeWriter>();
        this.settings = settings;
        this.errorReporter = errorReporter;
    }

    public void addWriter(SafeWriter writer) {
        writers.add(writer);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        for (SafeWriter writer : writers) {
            writer.write(cbuf, off, len);
        }
    }

    public boolean hasPendingData() {
        for (SafeWriter writer : writers) {
            if (writer.hasPendingData()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasOutputs() {
        return !writers.isEmpty();
    }

    public boolean sendData() {
        boolean success = true;
        for (SafeWriter writer : writers) {
            try {
                writer.sendData();
            } catch (IOException e) {
                success = false;
                errorReporter.logWarning("Failed to send events to OpenSearch: " + e.getMessage());
                if (settings.isErrorsToStderr()) {
                    System.err.println("[" + new Date().toString() + "] Failed to send events to OpenSearch: " + e.getMessage());
                }
            }
        }
        return success;
    }

    @Override
    public void flush() throws IOException {
        // No-op
    }

    @Override
    public void close() throws IOException {
        // No-op
    }
}
