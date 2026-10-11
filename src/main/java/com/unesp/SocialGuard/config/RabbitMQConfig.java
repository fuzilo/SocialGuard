package com.unesp.SocialGuard.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    DirectExchange batchExchange(@Value("${app.rabbitmq.batch.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue batchQueue(
            @Value("${app.rabbitmq.batch.queue}") String queue,
            @Value("${app.rabbitmq.batch.dead-letter-exchange}") String deadLetterExchange,
            @Value("${app.rabbitmq.batch.dead-letter-routing-key}") String deadLetterRoutingKey) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    @Bean
    Binding batchBinding(
            @Qualifier("batchQueue") Queue batchQueue,
            @Qualifier("batchExchange") DirectExchange batchExchange,
            @Value("${app.rabbitmq.batch.routing-key}") String routingKey) {
        return BindingBuilder.bind(batchQueue).to(batchExchange).with(routingKey);
    }

    @Bean
    DirectExchange batchDeadLetterExchange(
            @Value("${app.rabbitmq.batch.dead-letter-exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue batchDeadLetterQueue(@Value("${app.rabbitmq.batch.dead-letter-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding batchDeadLetterBinding(
            @Qualifier("batchDeadLetterQueue") Queue batchDeadLetterQueue,
            @Qualifier("batchDeadLetterExchange") DirectExchange batchDeadLetterExchange,
            @Value("${app.rabbitmq.batch.dead-letter-routing-key}") String routingKey) {
        return BindingBuilder.bind(batchDeadLetterQueue).to(batchDeadLetterExchange).with(routingKey);
    }
}
