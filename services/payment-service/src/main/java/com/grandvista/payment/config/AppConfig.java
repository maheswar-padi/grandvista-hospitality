package com.grandvista.payment.config;

import com.grandvista.platform.client.CorrelationIdPropagationInterceptor;
import com.grandvista.platform.client.HttpClients;
import com.grandvista.platform.client.ServiceAuthProperties;
import com.grandvista.platform.client.ServiceTokenInterceptor;
import com.grandvista.platform.client.ServiceTokenProvider;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import com.grandvista.payment.service.HttpOutboxPublisher;
import com.grandvista.payment.service.OutboxPublisher;
import com.grandvista.payment.service.RabbitOutboxPublisher;
import com.grandvista.platform.messaging.ConfirmedRabbitPublisher;
import com.grandvista.platform.messaging.RabbitDeclarations;
import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Builder whose clients resolve {@code http://<service-name>} through Eureka + Spring Cloud LoadBalancer.
     * It starts from Spring Boot's configurer so it shares the application's JSON settings (ISO dates).
     */
    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(@LoadBalanced RestClient.Builder builder, ServiceAuthProperties properties,
            Clock clock) {
        return new ServiceTokenProvider(builder, properties, clock);
    }

    @Bean
    RestClient bookingServiceClient(@LoadBalanced RestClient.Builder builder, ServiceTokenProvider tokens,
            PaymentProperties properties) {
        return builder.clone()
                .baseUrl(properties.outbox().bookingServiceUrl())
                .requestFactory(HttpClients.requestFactory(java.time.Duration.ofSeconds(2), properties.outbox().httpTimeout()))
                .requestInterceptor(new CorrelationIdPropagationInterceptor())
                .requestInterceptor(new ServiceTokenInterceptor(tokens))
                .build();
    }

    /** Event transport selected by {@code gv.payment.outbox.transport} (RABBIT by default, or HTTP). */
    @Bean
    OutboxPublisher outboxPublisher(PaymentProperties properties, RestClient bookingServiceClient,
            org.springframework.beans.factory.ObjectProvider<RabbitTemplate> rabbitTemplate) {
        if (properties.outbox().transport() == PaymentProperties.Outbox.Transport.HTTP) {
            return new HttpOutboxPublisher(bookingServiceClient);
        }
        return new RabbitOutboxPublisher(new ConfirmedRabbitPublisher(rabbitTemplate.getObject(), Duration.ofSeconds(5)));
    }

    /** The producer declares the exchanges it publishes to (idempotent). Consumers declare their own queues. */
    @Bean
    Declarables eventExchanges() {
        return new Declarables(RabbitDeclarations.eventsExchange(), RabbitDeclarations.deadLetterExchange());
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("GrandVista Payment Service").version("v1")
                        .description("Payments against a SIMULATED provider (no real money), refunds, provider webhooks."))
                .addServersItem(new Server().url("/").description("Through the API gateway"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
