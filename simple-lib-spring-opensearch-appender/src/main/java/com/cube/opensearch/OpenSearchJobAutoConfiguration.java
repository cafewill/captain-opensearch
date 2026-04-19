package com.cube.opensearch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;

/**
 * Job 앱(non-web)에서 MdcJobFilter 를 자동 등록하는 AutoConfiguration.
 *
 * 활성화 조건:
 *   - @ConditionalOnNotWebApplication : 웹 앱(spring-boot-starter-web)에서는 비활성
 *   - @ConditionalOnMissingBean(TaskScheduler) : 앱이 이미 TaskScheduler 를 정의한 경우 비활성
 *
 * Job 앱의 logback-spring.xml 에 class="com.cube.opensearch.OpenSearchJobAppender" 를 선언하면
 * 이 AutoConfiguration 이 MdcJobFilter(@Configuration + @Bean TaskScheduler)를 자동 등록하여
 * 모든 스케줄러 스레드에 app · env · instance_id MDC 를 주입한다.
 */
@AutoConfiguration
@ConditionalOnNotWebApplication
@ConditionalOnMissingBean(TaskScheduler.class)
@Import(OpenSearchJobAppender.MdcJobFilter.class)
public class OpenSearchJobAutoConfiguration {
}
