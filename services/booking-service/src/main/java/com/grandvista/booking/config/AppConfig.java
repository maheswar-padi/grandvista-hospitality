package com.grandvista.booking.config;

import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.platform.client.CorrelationIdPropagationInterceptor;
import com.grandvista.platform.client.HttpClients;
import com.grandvista.platform.client.Retrier;
import com.grandvista.platform.client.ServiceAuthProperties;
import com.grandvista.platform.client.ServiceTokenInterceptor;
import com.grandvista.platform.client.ServiceTokenProvider;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.time.Clock;
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
     * Clients built from this builder resolve {@code http://room-service} etc. via Eureka + LoadBalancer.
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

    /** Bounded retries (3 attempts, exponential backoff with jitter) for idempotent internal calls only. */
    @Bean
    Retrier retrier() {
        return new Retrier(Retrier.Policy.defaults());
    }

    @Bean
    RoomServiceClient roomServiceClient(@LoadBalanced RestClient.Builder builder, ServiceTokenProvider tokens,
            BookingProperties properties, Retrier retrier) {
        return new RoomServiceClient(client(builder, tokens, properties.rooms()), retrier);
    }

    @Bean
    PaymentServiceClient paymentServiceClient(@LoadBalanced RestClient.Builder builder, ServiceTokenProvider tokens,
            BookingProperties properties, Retrier retrier) {
        return new PaymentServiceClient(client(builder, tokens, properties.payments()), retrier);
    }

    private static RestClient client(RestClient.Builder builder, ServiceTokenProvider tokens,
            BookingProperties.Downstream downstream) {
        return builder.clone()
                .baseUrl(downstream.baseUrl())
                .requestFactory(HttpClients.requestFactory(downstream.connectTimeout(), downstream.readTimeout()))
                .requestInterceptor(new CorrelationIdPropagationInterceptor())
                .requestInterceptor(new ServiceTokenInterceptor(tokens))
                .build();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("GrandVista Booking Service").version("v1")
                        .description("Reservations and the booking → inventory → payment saga."))
                .addServersItem(new Server().url("/").description("Through the API gateway"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
