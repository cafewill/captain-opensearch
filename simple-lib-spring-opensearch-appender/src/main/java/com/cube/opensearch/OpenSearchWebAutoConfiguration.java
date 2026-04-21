package com.cube.opensearch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.web.filter.OncePerRequestFilter",
        "jakarta.servlet.http.HttpServletRequest"
})
@ConditionalOnProperty(prefix = "cube.opensearch.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenSearchWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OpenSearchWebAppender.MdcWebFilter cubeOpenSearchMdcWebFilter() {
        return new OpenSearchWebAppender.MdcWebFilter();
    }
}
