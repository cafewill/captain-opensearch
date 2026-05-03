package com.github.cafewill.opensearch;


import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;

import org.hamcrest.core.IsInstanceOf;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.github.cafewill.opensearch.AbstractOpenSearchAppender;
import com.github.cafewill.opensearch.ClassicOpenSearchPublisher;
import com.github.cafewill.opensearch.OpenSearchAppender;
import com.github.cafewill.opensearch.config.OpenSearchProperties;
import com.github.cafewill.opensearch.config.Settings;
import com.github.cafewill.opensearch.util.ErrorReporter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class OpenSearchAppenderTest {


    @Mock
    private ClassicOpenSearchPublisher opensearchPublisher;
    @Mock
    private ErrorReporter errorReporter;
    @Mock
    private Settings settings;
    @Mock
    private OpenSearchProperties opensearchProperties;
    @Mock
    private Context mockedContext;

    private boolean publisherSet = false;
    private boolean errorReporterSet = false;
    private AbstractOpenSearchAppender appender;

    @Before
    public void setUp() {

        appender = new OpenSearchAppender() {
            @Override
            protected ClassicOpenSearchPublisher buildOpenSearchPublisher() throws IOException {
                publisherSet = true;
                return opensearchPublisher;
            }

            @Override
            protected ErrorReporter getErrorReporter() {
                errorReporterSet = true;
                return errorReporter;
            }
        };
    }

    @Test
    public void should_set_the_collaborators_when_started() {
        appender.start();


        assertThat(publisherSet, is(true));
        assertThat(errorReporterSet, is(true));
    }

    @Test
    public void should_throw_error_when_publisher_setup_fails_during_startup() {
        OpenSearchAppender appender = new OpenSearchAppender() {
            @Override
            protected ClassicOpenSearchPublisher buildOpenSearchPublisher() throws IOException {
                throw new IOException("Failed to start Publisher");
            }
        };

        try {
            appender.start();
        } catch (Exception e) {
            assertThat(e, IsInstanceOf.instanceOf(RuntimeException.class));
            assertThat(e.getMessage(), is("java.io.IOException: Failed to start Publisher"));
        }


    }

    @Test
    public void should_not_publish_events_when_logger_set() {
        String loggerName = "opensearch-debug-log";
        ILoggingEvent eventToLog = mock(ILoggingEvent.class);
        given(eventToLog.getLoggerName()).willReturn(loggerName);


        appender.setLoggerName(loggerName);
        appender.start();


        appender.append(eventToLog);

        verifyNoInteractions(opensearchPublisher);
    }


    @Test
    public void should_not_publish_events_when_errorlogger_set() {
        String errorLoggerName = "opensearch-error-log";
        ILoggingEvent eventToLog = mock(ILoggingEvent.class);
        given(eventToLog.getLoggerName()).willReturn(errorLoggerName);


        appender.setErrorLoggerName(errorLoggerName);
        appender.start();


        appender.append(eventToLog);

        verifyNoInteractions(opensearchPublisher);
    }


    @Test
    public void should_publish_events_when_loggername_is_null() {
        ILoggingEvent eventToPublish = mock(ILoggingEvent.class);
        given(eventToPublish.getLoggerName()).willReturn(null);
        String errorLoggerName = "es-error";

        appender.setErrorLoggerName(errorLoggerName);
        appender.start();


        appender.append(eventToPublish);

        verify(opensearchPublisher, times(1)).addEvent(eventToPublish);
    }


    @Test
    public void should_publish_events_when_loggername_is_different_from_the_opensearch_loggers() {
        ILoggingEvent eventToPublish = mock(ILoggingEvent.class);
        String differentLoggerName = "different-logger";
        String errorLoggerName = "es-errors";
        given(eventToPublish.getLoggerName()).willReturn(differentLoggerName);


        appender.setErrorLoggerName(errorLoggerName);
        appender.start();


        appender.append(eventToPublish);

        verify(opensearchPublisher, times(1)).addEvent(eventToPublish);
    }

    @Test
    public void should_create_error_reporter_with_same_context() {
        OpenSearchAppender appender = new OpenSearchAppender() {
            @Override
            public Context getContext() {
                return mockedContext;
            }
        };

        ErrorReporter errorReporter = appender.getErrorReporter();

        assertThat(errorReporter.getContext(), is(mockedContext));
    }


    @Test
    public void should_delegate_setters_to_settings() throws MalformedURLException {
        OpenSearchAppender appender = new OpenSearchAppender(settings);
        boolean includeCallerData = false;
        boolean errorsToStderr = false;
        boolean rawJsonMessage = false;
        boolean includeMdc = true;
        String index = "app-logs";
        String type = "appenderType";
        int maxQueueSize = 10;
        String logger = "opensearch-logger";
        String url = "http://myopensearch.mycompany.com";
        String errorLogger = "opensearch-error-logger";
        int maxRetries = 10000;
        int aSleepTime = 10000;
        int readTimeout = 10000;
        int connectTimeout = 5000;
        boolean includeKvp = true;

        appender.setIncludeCallerData(includeCallerData);
        appender.setSleepTime(aSleepTime);
        appender.setReadTimeout(readTimeout);
        appender.setErrorsToStderr(errorsToStderr);
        appender.setLogsToStderr(errorsToStderr);
        appender.setMaxQueueSize(maxQueueSize);
        appender.setIndex(index);
        appender.setType(type);
        appender.setUrl(url);
        appender.setLoggerName(logger);
        appender.setErrorLoggerName(errorLogger);
        appender.setMaxRetries(maxRetries);
        appender.setConnectTimeout(connectTimeout);
        appender.setRawJsonMessage(rawJsonMessage);
        appender.setIncludeMdc(includeMdc);
        appender.setIncludeKvp(includeKvp);

        verify(settings, times(1)).setReadTimeout(readTimeout);
        verify(settings, times(1)).setSleepTime(aSleepTime);
        verify(settings, times(1)).setIncludeCallerData(includeCallerData);
        verify(settings, times(1)).setErrorsToStderr(errorsToStderr);
        verify(settings, times(1)).setLogsToStderr(errorsToStderr);
        verify(settings, times(1)).setMaxQueueSize(maxQueueSize);
        verify(settings, times(1)).setIndex(index);
        verify(settings, times(1)).setType(type);
        verify(settings, times(1)).setUrl(new URL(url));
        verify(settings, times(1)).setLoggerName(logger);
        verify(settings, times(1)).setErrorLoggerName(errorLogger);
        verify(settings, times(1)).setMaxRetries(maxRetries);
        verify(settings, times(1)).setConnectTimeout(connectTimeout);
        verify(settings, times(1)).setRawJsonMessage(rawJsonMessage);
        verify(settings, times(1)).setIncludeMdc(includeMdc);
        verify(settings, times(1)).setIncludeKvp(includeKvp);
    }


}
