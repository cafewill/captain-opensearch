package com.cube.opensearch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.scheduling.TaskScheduler")
@ConditionalOnProperty(prefix = "cube.opensearch.job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenSearchJobAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OpenSearchJobAppender.MdcJobFilter cubeOpenSearchMdcJobFilter() {
        return new OpenSearchJobAppender.MdcJobFilter();
    }
}
