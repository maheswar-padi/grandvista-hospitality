package com.grandvista.payment.provider.razorpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Active only with {@code gv.payment.provider=razorpay}; refuses to start without test-mode keys. */
@Configuration
@ConditionalOnProperty(name = "gv.payment.provider", havingValue = "razorpay")
public class RazorpayConfig {

    /** A plain (not load-balanced) client: api.razorpay.com is an external host, not a Eureka service. */
    @Bean
    RazorpayClient razorpayClient(RestClientBuilderConfigurer configurer, RazorpayProperties properties) {
        properties.requireConfigured();
        return new RazorpayClient(configurer.configure(RestClient.builder()), properties);
    }

    @Bean
    RazorpayPaymentProvider razorpayPaymentProvider(RazorpayClient client, RazorpayProperties properties) {
        return new RazorpayPaymentProvider(client, properties);
    }
}
