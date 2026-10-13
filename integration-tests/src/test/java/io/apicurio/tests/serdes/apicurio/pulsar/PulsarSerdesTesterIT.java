package io.apicurio.tests.serdes.apicurio.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.apicurio.deployment.Constants.SMOKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Pulsar consume and produce helper itself, with no broker and no registry. A consume that does
 * not get its messages must end on its own and leave the consumer open, because the caller reuses it.
 */
@Tag(SMOKE)
class PulsarSerdesTesterIT {

    private static final String TOPIC = "topic";

    private final PulsarSerdesTester<String, String> tester = new PulsarSerdesTester<>();

    @Test
    void consumeReturnsOnceTheExpectedMessagesArrive() throws Exception {
        Consumer<String> consumer = mockConsumer();
        Message<String> message = mockMessage("value");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(message);

        tester.consumeMessages(consumer, TOPIC, 2, "value"::equals);

        verify(consumer, times(2)).receive(anyInt(), any(TimeUnit.class));
        verify(consumer, never()).close();
    }

    @Test
    void consumeTimeoutEndsTheThreadByItselfAndLeavesTheConsumerOpen() throws Exception {
        Consumer<String> consumer = mockConsumer();
        CountDownLatch receiveEntered = new CountDownLatch(1);
        AtomicReference<String> receiveThread = new AtomicReference<>();
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenAnswer(invocation -> {
            receiveThread.set(Thread.currentThread().getName());
            receiveEntered.countDown();
            // The real receive(timeout) returns null after its timeout.
            Thread.sleep(200);
            return null;
        });

        // One expected message gives a 2.7 s wait, after which the thread ends by itself with a count that
        // falls short of the expected one.
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> tester.consumeMessages(consumer, TOPIC, 1, null));

        assertEquals(1, failure.getExpected().getValue());
        assertEquals(0, failure.getActual().getValue());
        assertTrue(receiveEntered.await(5, TimeUnit.SECONDS), "receive() was never called");
        assertEquals("pulsar-serdes-consumer-" + TOPIC, receiveThread.get());
        verify(consumer, never()).close();
    }

    @Test
    void consumeKeepsPollingAfterAReceiveThatTimedOut() throws Exception {
        Consumer<String> consumer = mockConsumer();
        Message<String> message = mockMessage("value");
        // A message, a receive that times out, then another message: the loop must go on after the null.
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(message, null, message);

        tester.consumeMessages(consumer, TOPIC, 2, "value"::equals);

        verify(consumer, times(3)).receive(anyInt(), any(TimeUnit.class));
        verify(consumer, never()).close();
    }

    @Test
    void consumeCountsTheMessagesReceivedBeforeTheDeadline() throws Exception {
        Consumer<String> consumer = mockConsumer();
        Message<String> message = mockMessage("value");
        // One message, then nothing until the deadline.
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(message).thenAnswer(invocation -> {
            Thread.sleep(200);
            return null;
        });

        // Two expected messages give a 3.4 s wait.
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> tester.consumeMessages(consumer, TOPIC, 2, "value"::equals));

        assertEquals(2, failure.getExpected().getValue());
        assertEquals(1, failure.getActual().getValue());
        verify(consumer, never()).close();
    }

    @Test
    void consumeReportsAValidatorFailure() throws Exception {
        Consumer<String> consumer = mockConsumer();
        Message<String> unexpected = mockMessage("unexpected");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(unexpected);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> tester.consumeMessages(consumer, TOPIC, 1, "value"::equals));

        assertInstanceOf(AssertionError.class, e.getCause());
        assertTrue(e.getCause().getMessage().startsWith("Consumed record validation failed"),
                e.getCause().getMessage());
        verify(consumer, never()).close();
    }

    @Test
    void produceRunsOnANamedThreadAndTimesOutWhenSendBlocks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sendEntered = new CountDownLatch(1);
        AtomicReference<String> sendThread = new AtomicReference<>();
        Producer<String> producer = mockProducer();
        when(producer.send(any(String.class))).thenAnswer(invocation -> {
            sendThread.set(Thread.currentThread().getName());
            sendEntered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        });

        try {
            // One message gives a 2.7 s wait.
            assertThrows(TimeoutException.class,
                    () -> tester.produceMessages(producer, TOPIC, count -> "value", 1, false));
        } finally {
            release.countDown();
        }

        assertTrue(sendEntered.await(5, TimeUnit.SECONDS), "send() was never called");
        assertEquals("pulsar-serdes-producer-" + TOPIC, sendThread.get());
    }

    @Test
    void produceFailureSurfacesAsTheCauseOfAnExecutionException() throws Exception {
        Producer<String> producer = mockProducer();
        PulsarClientException failure = new PulsarClientException("broker unreachable");
        when(producer.send(any(String.class))).thenThrow(failure);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> tester.produceMessages(producer, TOPIC, count -> "value", 1, false));

        assertSame(failure, e.getCause());
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> mockConsumer() {
        return mock(Consumer.class);
    }

    @SuppressWarnings("unchecked")
    private static Producer<String> mockProducer() {
        return mock(Producer.class);
    }

    @SuppressWarnings("unchecked")
    private static Message<String> mockMessage(String value) {
        Message<String> message = mock(Message.class);
        when(message.getValue()).thenReturn(value);
        when(message.getTopicName()).thenReturn(TOPIC);
        return message;
    }
}
