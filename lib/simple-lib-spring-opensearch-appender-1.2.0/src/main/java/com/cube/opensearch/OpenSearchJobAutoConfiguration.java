package com.cube.opensearch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

@AutoConfiguration
@ConditionalOnClass({TaskScheduler.class, OpenSearchJobAppender.class})
@ConditionalOnProperty(prefix = "cube.opensearch.job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenSearchJobAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OpenSearchJobAppender.MdcJobFilter cubeOpenSearchMdcJobFilter(
            @Value("${spring.application.name:app}") String appName,
            @Value("${spring.profiles.active:default}") String env) {
        return new OpenSearchJobAppender.MdcJobFilter(appName, env);
    }

    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public TaskScheduler cubeOpenSearchTaskScheduler(OpenSearchJobAppender.MdcJobFilter mdcJobFilter) {
        return mdcJobFilter.taskScheduler();
    }
}
