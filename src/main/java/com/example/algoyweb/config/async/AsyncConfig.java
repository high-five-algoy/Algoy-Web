package com.example.algoyweb.config.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * EnableAsync를 통해서 비동기 처리를 수행하는 부분에 대한 설정 부분
 *
 * @author 조아라
 * @since 2026.04.23
 *
 * 여기서는 threadPoolTaskExecutor를 이용하여 threadPoolTaskExecutor의 이름을 지정하여 Executor를 지정
 */

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "recommendationTaskExecutor")
    public Executor recommendationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //ThreadPoolExecutor의 기본 유지 스레드 수를 반환
        executor.setCorePoolSize(2);
        //ThreadPoolExecutor의 최대 스레드 수를 반환
        executor.setMaxPoolSize(4);
        //ThreadPoolExecutor의 BlockingQueue 용량을 반환
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("recommendation-");
        executor.initialize();
        return executor;
    }
}
