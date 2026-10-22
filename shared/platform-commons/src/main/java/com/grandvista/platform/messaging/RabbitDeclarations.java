package com.grandvista.platform.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/** Broker objects shared by producers and consumers; declaring them is idempotent. */
public final class RabbitDeclarations {

    private RabbitDeclarations() {
    }

    public static TopicExchange eventsExchange() {
        return new TopicExchange(EventTopology.EXCHANGE, true, false);
    }

    public static DirectExchange deadLetterExchange() {
        return new DirectExchange(EventTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * A durable consumer queue bound to the events exchange, plus its dead-letter queue. Messages the consumer
     * rejects without requeue (after its retries) are routed to {@code <queue>.dlq} for inspection or replay.
     */
    public static Declarables consumerQueue(String queue, String... routingKeys) {
        TopicExchange events = eventsExchange();
        DirectExchange dlx = deadLetterExchange();
        String dlqName = EventTopology.deadLetterQueue(queue);
        Queue main = QueueBuilder.durable(queue)
                .deadLetterExchange(EventTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(dlqName)
                .build();
        Queue dlq = QueueBuilder.durable(dlqName).build();
        List<Declarable> all = new ArrayList<>(List.of(events, dlx, main, dlq, BindingBuilder.bind(dlq).to(dlx).with(dlqName)));
        for (String key : routingKeys) {
            Binding binding = BindingBuilder.bind(main).to(events).with(key);
            all.add(binding);
        }
        return new Declarables(all);
    }
}
