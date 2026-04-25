package com.cube.opensearch;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.Map;

/**
 * 하위호환용 래퍼.
 * 1.2.0부터는 OpenSearchAppender 사용을 권장하고,
 * Job 차이는 MdcJobFilter에서만 처리한다.
 */
@Deprecated
public class OpenSearchJobAppender extends OpenSearchAppender {

    public static class MdcJobFilter {
        private final String appName;
        private final String env;

        public MdcJobFilter(@Value("${spring.application.name:app}") String appName,
                            @Value("${spring.profiles.active:default}") String env) {
            this.appName = appName;
            this.env = env;
        }

        public TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(4);
            scheduler.setThreadNamePrefix("scheduled-");
            scheduler.setTaskDecorator(taskDecorator(appName, env));
            scheduler.initialize();
            return scheduler;
        }

        private TaskDecorator taskDecorator(String appName, String env) {
            return runnable -> {
                Map<String, String> parent = MDC.getCopyOfContextMap();
                return () -> {
                    Map<String, String> previous = MDC.getCopyOfContextMap();
                    try {
                        if (parent != null) {
                            MDC.setContextMap(parent);
                        } else {
                            MDC.clear();
                        }
                        MDC.put("app", appName);
                        MDC.put("env", env);
                        MDC.put("instance_id", OpenSearchSender.resolveInstanceId());
                        MDC.put("job_name", Thread.currentThread().getName());
                        MDC.put("schedule_id", "scheduler");
                        MDC.put("trigger_time", Instant.now().toString());
                        runnable.run();
                    } finally {
                        if (previous != null) {
                            MDC.setContextMap(previous);
                        } else {
                            MDC.clear();
                        }
                    }
                };
            };
        }
    }
}
