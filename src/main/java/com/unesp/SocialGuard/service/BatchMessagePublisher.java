package com.unesp.SocialGuard.service;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class BatchMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.batch.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.batch.routing-key}")
    private String routingKey;

    @Value("${app.rabbitmq.publisher-confirm-timeout}")
    private java.time.Duration confirmTimeout;

    public void publish(String batchId) {
        CorrelationData correlationData = new CorrelationData(batchId + "-" + UUID.randomUUID());
        rabbitTemplate.convertAndSend(exchange, routingKey, batchId, correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejeitou a publicação: " + confirm.getReason());
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException("A mensagem não foi roteada para nenhuma fila.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido aguardando confirmação do RabbitMQ.", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Não houve confirmação da publicação pelo RabbitMQ.", ex);
        }
    }
}
