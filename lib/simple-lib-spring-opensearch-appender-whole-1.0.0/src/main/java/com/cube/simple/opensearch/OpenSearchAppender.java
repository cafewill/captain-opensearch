package com.cube.simple.opensearch;

import java.io.IOException;

import com.cube.simple.opensearch.config.Settings;

import ch.qos.logback.classic.Level;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;

public class OpenSearchAppender extends AbstractOpenSearchAppender<ILoggingEvent> {

    public OpenSearchAppender() {
    }

    public OpenSearchAppender(Settings settings) {
        super(settings);
    }

    @Override
    protected void appendInternal(ILoggingEvent eventObject) {

        String targetLogger = eventObject.getLoggerName();

        String loggerName = settings.getLoggerName();
        if (loggerName != null && loggerName.equals(targetLogger)) {
            return;
        }

        String errorLoggerName = settings.getErrorLoggerName();
        if (errorLoggerName != null && errorLoggerName.equals(targetLogger)) {
            return;
        }

        eventObject.prepareForDeferredProcessing();
        if (settings.isIncludeCallerData()) {
            eventObject.getCallerData();
        }
        Level configLevel = settings.getAutoStackTraceLevel();
        if (configLevel != null && eventObject instanceof LoggingEvent && eventObject.getThrowableProxy() == null) {
            LoggingEvent le = (LoggingEvent) eventObject;
            Level eventLevel = le.getLevel();
            if (eventLevel != null && eventLevel.levelInt >= configLevel.levelInt) {
                Exception ex = new Exception("auto generated stacktrace");
                ex.setStackTrace(le.getCallerData());
                le.setThrowableProxy(new ThrowableProxy(ex));
            }
        }

        publishEvent(eventObject);
    }

    protected ClassicOpenSearchPublisher buildOpenSearchPublisher() throws IOException {
        return new ClassicOpenSearchPublisher(getContext(), errorReporter, settings, opensearchProperties, headers);
    }


}
