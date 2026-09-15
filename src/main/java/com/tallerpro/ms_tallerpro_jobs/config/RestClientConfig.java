package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Cliente HTTP hacia ms-tallerpro-catalog (T-02.04, T-02.09), con timeouts acotados. */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(JobsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.catalog().connectTimeoutMs());
        factory.setReadTimeout(properties.catalog().readTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.catalog().baseUrl())
                .requestFactory(factory)
                .build();
    }
}
