package com.cube.opensearch;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass({HttpServletRequest.class, OpenSearchWebAppender.class})
@ConditionalOnProperty(prefix = "cube.opensearch.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenSearchWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OpenSearchWebAppender.MdcWebFilter cubeOpenSearchMdcWebFilter() {
        return new OpenSearchWebAppender.MdcWebFilter();
    }
}
