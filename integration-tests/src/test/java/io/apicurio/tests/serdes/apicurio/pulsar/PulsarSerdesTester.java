package io.apicurio.tests.serdes.apicurio.pulsar;

import io.apicurio.registry.utils.tests.TestUtils;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PulsarSerdesTester<P, C> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PulsarSerdesTester.class);

    private static final int MILLIS_PER_MESSAGE = 700;
    private static final int RECEIVE_POLL_MILLIS = 1000;
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String MAC_OS_BOOTSTRAP_SERVERS = "docker.host.internal:9092";

    private boolean autoClose = true;

    public PulsarSerdesTester() {
        // empty
    }

    public void produceMessages(Producer<P> producer, String topicName, DataGenerator<P> dataGenerator,
            int messageCount, boolean retry) throws Exception {

        if (retry) {
            TestUtils.retry(() -> produceMessages(producer, topicName, dataGenerator, messageCount));
        } else {
            produceMessages(producer, topicName, dataGenerator, messageCount);
        }
    }

    private void produceMessages(Producer<P> producer, String topicName, DataGenerator<P> dataGenerator,
            int messageCount) throws ExecutionException, InterruptedException, TimeoutException {
        FutureTask<Integer> resultPromise = new FutureTask<>(() -> {

            int producedMessages = 0;

            try {
                while (producedMessages < messageCount) {
                    P data = dataGenerator.generate(producedMessages);
                    LOGGER.info("Sending message {} to topic {}", data, topicName);
                    producer.send(data);
                    producedMessages++;

                }
                LOGGER.info("Produced {} messages", producedMessages);

            } catch (PulsarClientException e) {
                throw new RuntimeException(e);
            }

            return producedMessages;
        });
        // A named thread of its own, so a timeout can dump this producer's stack.
        Thread producerThread = new Thread(resultPromise, "pulsar-serdes-producer-" + topicName);
        producerThread.setDaemon(true);
        producerThread.start();

        try {
            Integer messagesSent = resultPromise.get((MILLIS_PER_MESSAGE * messageCount) + 2000,
                    TimeUnit.MILLISECONDS);
            assertEquals(messageCount, messagesSent.intValue());
        } catch (TimeoutException e) {
            LOGGER.error("Producer for topic {} did not finish in time. Stack of {} ({}):\n{}", topicName,
                    producerThread.getName(), producerThread.getState(),
                    formatStack(producerThread.getStackTrace()));
            throw e;
        }
    }

    public void consumeMessages(Consumer<C> consumer, String topicName, int messageCount,
            Predicate<C> dataValidator) throws Exception {
        long waitMillis = (MILLIS_PER_MESSAGE * messageCount) + 2000;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        // A named thread of its own, so a timeout can dump this consumer's stack.
        FutureTask<Integer> resultPromise = new FutureTask<>(
                () -> receiveUntilConsumed(consumer, messageCount, dataValidator, deadlineNanos));
        Thread consumerThread = new Thread(resultPromise, "pulsar-serdes-consumer-" + topicName);
        consumerThread.setDaemon(true);
        consumerThread.start();

        try {
            // The thread stops at its deadline after at most one more receive, plus a second for scheduling.
            Integer messagesConsumed = resultPromise.get(waitMillis + RECEIVE_POLL_MILLIS + 1000,
                    TimeUnit.MILLISECONDS);
            assertEquals(messageCount, messagesConsumed.intValue());
        } catch (TimeoutException e) {
            LOGGER.error("Consumer for topic {} did not finish in time. Stack of {} ({}):\n{}", topicName,
                    consumerThread.getName(), consumerThread.getState(),
                    formatStack(consumerThread.getStackTrace()));
            throw e;
        }
    }

    /**
     * Receives with a short timeout, so the thread ends on its own at the deadline and leaves the consumer
     * open for the caller to reuse. Pulsar has no wakeup(), and closing a consumer from another thread
     * would break a test that consumes in several rounds.
     */
    private int receiveUntilConsumed(Consumer<C> consumer, int messageCount, Predicate<C> dataValidator,
            long deadlineNanos) throws PulsarClientException {
        int consumedMessages = 0;

        while (consumedMessages < messageCount && System.nanoTime() - deadlineNanos < 0) {

            Message<C> message = consumer.receive(RECEIVE_POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (message == null) {
                LOGGER.info("None found");
                continue;
            }

            if (dataValidator != null) {
                assertTrue(dataValidator.test(message.getValue()), "Consumed record validation failed");
            }

            consumedMessages++;
            LOGGER.info("{} {}", message.getTopicName(), message.getValue());
        }

        LOGGER.info("Consumed {} messages", consumedMessages);
        return consumedMessages;
    }

    private static String formatStack(StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : stack) {
            out.append("\tat ").append(frame).append('\n');
        }
        return out.toString();
    }

    @FunctionalInterface
    public static interface DataGenerator<T> {

        public T generate(int count);

    }

    @FunctionalInterface
    public static interface Validator {

        public boolean validate() throws Exception;

    }
}