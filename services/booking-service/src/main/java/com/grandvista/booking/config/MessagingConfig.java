package com.grandvista.booking.config;

import com.grandvista.booking.messaging.PaymentEventListener;
import com.grandvista.platform.messaging.ConfirmedRabbitPublisher;
import com.grandvista.platform.messaging.RabbitDeclarations;
import java.time.Duration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ objects owned by the booking-service: its payment-event queue (+ DLQ) and the event publisher. */
@Configuration
public class MessagingConfig {

    @Bean
    Declarables paymentEventQueue() {
        return RabbitDeclarations.consumerQueue(PaymentEventListener.QUEUE, "payment.succeeded", "payment.failed");
    }

    @Bean
    ConfirmedRabbitPublisher confirmedRabbitPublisher(RabbitTemplate rabbitTemplate) {
        return new ConfirmedRabbitPublisher(rabbitTemplate, Duration.ofSeconds(5));
    }
}
