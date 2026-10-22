package com.grandvista.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.platform.messaging.ConfirmedRabbitPublisher;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.messaging.RabbitDeclarations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Publishes real outbox events to a real RabbitMQ broker and checks routing, headers and failure handling. */
@Testcontainers(disabledWithoutDocker = true)
class RabbitOutboxPublisherIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    static CachingConnectionFactory connections;
    static RabbitAdmin admin;
    static RabbitTemplate template;

    @BeforeAll
    static void connect() {
        connections = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connections.setUsername(RABBIT.getAdminUsername());
        connections.setPassword(RABBIT.getAdminPassword());
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        admin = new RabbitAdmin(connections);
        template = new RabbitTemplate(connections);
        admin.declareExchange(RabbitDeclarations.eventsExchange());
        admin.declareExchange(RabbitDeclarations.deadLetterExchange());
    }

    @AfterAll
    static void close() {
        connections.destroy();
    }

    private static OutboxEvent event(String type) {
        UUID id = UUID.randomUUID();
        return new OutboxEvent(id, UUID.randomUUID(), type,
                "{\"eventId\":\"" + id + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":1}", "corr-rabbit-0001",
                Instant.now());
    }

    @Test
    void unroutableEventIsReportedAsRetryableSoTheOutboxKeepsIt() {
        // No consumer queue is bound to payment.failed yet: the broker returns the message.
        var publisher = new RabbitOutboxPublisher(new ConfirmedRabbitPublisher(template, Duration.ofSeconds(5)));
        assertThatThrownBy(() -> publisher.publish(event(EventTopology.PAYMENT_FAILED)))
                .isInstanceOf(OutboxPublisher.RetryableDeliveryException.class)
                .hasMessageContaining("unroutable");
    }

    @Test
    void confirmedEventReachesTheBoundQueueWithIdTypeAndCorrelation() throws Exception {
        for (Declarable d : RabbitDeclarations.consumerQueue("test.payment-events", "payment.succeeded").getDeclarables()) {
            if (d instanceof org.springframework.amqp.core.Queue q) {
                admin.declareQueue(q);
            } else if (d instanceof org.springframework.amqp.core.Binding b) {
                admin.declareBinding(b);
            } else if (d instanceof org.springframework.amqp.core.Exchange e) {
                admin.declareExchange(e);
            }
        }
        var publisher = new RabbitOutboxPublisher(new ConfirmedRabbitPublisher(template, Duration.ofSeconds(5)));
        OutboxEvent event = event(EventTopology.PAYMENT_SUCCEEDED);

        publisher.publish(event);

        Message received = template.receive("test.payment-events", 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.getId().toString());
        assertThat(received.getMessageProperties().getType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.succeeded");
        assertThat((String) received.getMessageProperties().getHeader(EventTopology.CORRELATION_HEADER))
                .isEqualTo("corr-rabbit-0001");
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains("\"eventType\":\"PAYMENT_SUCCEEDED\"");
    }

    @Test
    void brokerOutageIsRetryable() {
        CachingConnectionFactory dead = new CachingConnectionFactory("localhost", 1);
        dead.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        var publisher = new RabbitOutboxPublisher(new ConfirmedRabbitPublisher(new RabbitTemplate(dead), Duration.ofSeconds(2)));
        assertThatThrownBy(() -> publisher.publish(event(EventTopology.PAYMENT_SUCCEEDED)))
                .isInstanceOf(OutboxPublisher.RetryableDeliveryException.class);
        dead.destroy();
    }
}
