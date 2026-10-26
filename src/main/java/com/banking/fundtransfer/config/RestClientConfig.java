package com.banking.fundtransfer.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient accountRestClient(@LoadBalanced RestClient.Builder builder, ServicesProperties services) {
        return create(builder, services.account());
    }

    @Bean
    public RestClient feeRestClient(@LoadBalanced RestClient.Builder builder, ServicesProperties services) {
        return create(builder, services.chargeFee());
    }

    @Bean
    public RestClient otpRestClient(@LoadBalanced RestClient.Builder builder, ServicesProperties services) {
        return create(builder, services.otp());
    }

    @Bean
    public RestClient cbsRestClient(@LoadBalanced RestClient.Builder builder, ServicesProperties services) {
        return create(builder, services.cbs());
    }

    @Bean
    public RestClient notificationRestClient(@LoadBalanced RestClient.Builder builder, ServicesProperties services) {
        return create(builder, services.notification());
    }

    private RestClient create(RestClient.Builder builder, String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);

        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}