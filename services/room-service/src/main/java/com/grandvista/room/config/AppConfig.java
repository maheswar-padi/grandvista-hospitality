package com.grandvista.room.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import com.grandvista.platform.client.CorrelationIdPropagationInterceptor;
import com.grandvista.platform.client.HttpClients;
import com.grandvista.platform.client.ServiceAuthProperties;
import com.grandvista.platform.client.ServiceTokenInterceptor;
import com.grandvista.platform.client.ServiceTokenProvider;
import com.grandvista.room.reconciliation.ReconciliationProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({ServiceAuthProperties.class, ReconciliationProperties.class})
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Clients built from it resolve {@code http://<service-name>} through Eureka + Spring Cloud LoadBalancer. */
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

    /** booking-service internal lookups used by the stock reconciliation (service token, correlation id). */
    @Bean
    RestClient bookingServiceLookupClient(@LoadBalanced RestClient.Builder builder, ServiceTokenProvider tokens,
            ReconciliationProperties properties) {
        return builder.clone()
                .baseUrl(properties.bookingServiceUrl())
                .requestFactory(HttpClients.requestFactory(Duration.ofSeconds(2), properties.httpTimeout()))
                .requestInterceptor(new CorrelationIdPropagationInterceptor())
                .requestInterceptor(new ServiceTokenInterceptor(tokens))
                .build();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("GrandVista Room Service").version("v1")
                        .description("Room catalogue, availability, dynamic pricing quotes and inventory administration."))
                .addServersItem(new Server().url("/").description("Through the API gateway"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
