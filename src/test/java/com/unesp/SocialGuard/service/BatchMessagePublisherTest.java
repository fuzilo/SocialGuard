package com.unesp.SocialGuard.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchMessagePublisherTest {

    private static final String EXCHANGE = "socialguard.batch";
    private static final String ROUTING_KEY = "batch.process";

    @Mock
    private RabbitTemplate rabbitTemplate;

    private BatchMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new BatchMessagePublisher(rabbitTemplate);
        ReflectionTestUtils.setField(publisher, "exchange", EXCHANGE);
        ReflectionTestUtils.setField(publisher, "routingKey", ROUTING_KEY);
        ReflectionTestUtils.setField(publisher, "confirmTimeout", Duration.ofMillis(200));
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void publishSendsOnlyBatchIdWithCorrelationData() {
        brokerResponds(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertDoesNotThrow(() -> publisher.publish("batch-1"));

        ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq("batch-1"), captor.capture());
        assertTrue(captor.getValue().getId().startsWith("batch-1-"));
    }

    @Test
    void nackFromBrokerFailsWithReason() {
        brokerResponds(correlation -> correlation.getFuture()
                .complete(new CorrelationData.Confirm(false, "queue full")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> publisher.publish("batch-1"));

        assertTrue(exception.getMessage().contains("queue full"));
    }

    @Test
    void unroutedMessageFails() {
        brokerResponds(correlation -> {
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", EXCHANGE, ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> publisher.publish("batch-1"));

        assertTrue(exception.getMessage().contains("roteada"));
    }

    @Test
    void missingConfirmationTimesOut() {
        // Nenhuma resposta do broker: o future nunca é completado.

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> publisher.publish("batch-1"));

        assertInstanceOf(TimeoutException.class, exception.getCause());
    }

    @Test
    void confirmationCompletedExceptionallyFails() {
        brokerResponds(correlation -> correlation.getFuture()
                .completeExceptionally(new RuntimeException("channel closed")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> publisher.publish("batch-1"));

        assertTrue(exception.getMessage().contains("confirmação"));
    }

    @Test
    void interruptionWhileWaitingRestoresInterruptFlag() {
        brokerResponds(correlation -> Thread.currentThread().interrupt());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> publisher.publish("batch-1"));

        assertInstanceOf(InterruptedException.class, exception.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private void brokerResponds(Consumer<CorrelationData> response) {
        doAnswer(invocation -> {
            response.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq("batch-1"), any(CorrelationData.class));
    }
}
