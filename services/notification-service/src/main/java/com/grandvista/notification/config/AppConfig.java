package com.grandvista.notification.config;

import com.grandvista.notification.messaging.DomainEventListener;
import com.grandvista.platform.messaging.RabbitDeclarations;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The notification queue receives every booking and payment event; rejected messages go to its DLQ. */
    @Bean
    Declarables notificationQueue() {
        return RabbitDeclarations.consumerQueue(DomainEventListener.QUEUE, "booking.#", "payment.#");
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("GrandVista Notification Service").version("v1")
                        .description("E-mail notifications driven by booking and payment events"))
                .addServersItem(new Server().url("/").description("Through the API gateway"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
