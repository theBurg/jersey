/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.media.sse.internal;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.Flow;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseSubscription;

import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.glassfish.jersey.internal.util.JerseyPublisher;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.LocalizationMessages;


/**
 * {@code SseEventSource} implementation.
 */
public class JerseySseEventSource implements javax.ws.rs.sse.SseEventSource, EventListener {

    private static final Level CONNECTION_ERROR_LEVEL = Level.FINE;
    private static final Logger LOGGER = Logger.getLogger(JerseySseEventSource.class.getName());

    private static final Consumer<SseSubscription> DEFAULT_SUBSCRIPTION_HANDLER =
            sseSubscription -> sseSubscription.request(Long.MAX_VALUE);

    private static final Consumer<Throwable> DEFAULT_ERROR_HANDLER =
            throwable -> LOGGER.log(
                    Level.WARNING,
                    LocalizationMessages.EVENT_SOURCE_DEFAULT_ONERROR(),
                    throwable);

    /**
     * {@code Last-Event-ID} header name constant.
     */
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    /**
     * Reactive streams Publisher implementation instance.
     */
    private final JerseyPublisher<InboundSseEvent> publisher = new JerseyPublisher<>();
    /**
     * Incoming SSE event processing task executor.
     */
    private final ScheduledExecutorService executor;
    /**
     * SseEventSource internal state.
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.READY);
    /**
     * SSE streaming resource target.
     */
    private final WebTarget endpoint;
    /**
     * Reconnect delay value.
     */
    private final long reconnectDelay;
    /**
     * Reconnect delay time unit.
     */
    private final TimeUnit reconnectTimeUnit;

    /**
     * Possible internal {@code SseEventSource} states.
     */
    private enum State {
        READY, OPEN, CLOSED
    }

    /**
     * Private constructor.
     * @param endpoint SSE resource {@link WebTarget}
     * @param name {@code SseEventSource} name - used for thread naming
     * @param reconnectDelay amount of time units before next reconnect attempt
     * @param reconnectTimeUnit time units to measure the reconnect attempts in
     */
    private JerseySseEventSource(final WebTarget endpoint,
                                 final String name,
                                 final long reconnectDelay,
                                 final TimeUnit reconnectTimeUnit) {

        this.endpoint = endpoint;
        this.reconnectDelay = reconnectDelay;
        this.reconnectTimeUnit = reconnectTimeUnit;

        final String threadName = (name == null ? createDefaultName(endpoint) : null);
        this.executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat(threadName + "-%d").setDaemon(true).build()
        );
    }

    /**
     * Creates default name to be used for thread naming (in case no explicit name provided).
     * @param endpoint SSE resource endpoint
     * @return default name based on {@code WebTarget}
     */
    private static String createDefaultName(final WebTarget endpoint) {
        return String.format("jersey-sse-event-source-[%s]", endpoint.getUri().toASCIIString());
    }

    @Override
    public void onEvent(final InboundEvent inboundEvent) {
        publisher.submit(inboundEvent);
    }

    public static class Builder extends javax.ws.rs.sse.SseEventSource.Builder {

        private WebTarget endpoint;
        private String name;
        private long reconnectDelay;
        private TimeUnit reconnectTimeUnit;

        @Override
        protected Builder target(final WebTarget endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        @Override
        public Builder named(final String name) {
            this.name = name;
            return this;
        }

        @Override
        public Builder reconnectingEvery(final long delay, final TimeUnit unit) {
            this.reconnectDelay = delay;
            this.reconnectTimeUnit = unit;
            return this;
        }

        @Override
        public JerseySseEventSource build() {
            return new JerseySseEventSource(endpoint, name, reconnectDelay, reconnectTimeUnit);
        }
    }

    @Override
    public void subscribe(final Consumer<InboundSseEvent> onEvent) {
        this.subscribe(DEFAULT_SUBSCRIPTION_HANDLER, onEvent, DEFAULT_ERROR_HANDLER, () -> {
        });
    }

    @Override
    public void subscribe(final Consumer<InboundSseEvent> onEvent, final Consumer<Throwable> onError) {
        this.subscribe(DEFAULT_SUBSCRIPTION_HANDLER, onEvent, onError, () -> {
        });
    }

    @Override
    public void subscribe(final Consumer<InboundSseEvent> onEvent, final Consumer<Throwable> onError, final Runnable onComplete) {
        this.subscribe(DEFAULT_SUBSCRIPTION_HANDLER, onEvent, onError, onComplete);
    }

    @Override
    public void subscribe(final Consumer<SseSubscription> onSubscribe,
                          final Consumer<InboundSseEvent> onEvent,
                          final Consumer<Throwable> onError,
                          final Runnable onComplete) {
        if (onSubscribe == null || onEvent == null || onError  == null || onComplete == null) {
            throw new IllegalStateException(LocalizationMessages.PARAMS_NULL());
        }

        publisher.subscribe(new Flow.Subscriber<InboundSseEvent>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                onSubscribe.accept(new SseSubscription() {
                    @Override
                    public void request(final long n) {
                        subscription.request(n);
                    }

                    @Override
                    public void cancel() {
                        subscription.cancel();
                    }
                });
            }

            @Override
            public void onNext(final InboundSseEvent item) {
                onEvent.accept(item);
            }

            @Override
            public void onError(final Throwable throwable) {
                onError.accept(throwable);
            }

            @Override
            public void onComplete() {
                onComplete.run();
            }
        });
    }

    @Override
    public void open() {
        if (!state.compareAndSet(State.READY, State.OPEN)) {
            switch (state.get()) {
                case CLOSED:
                    throw new IllegalStateException(LocalizationMessages.EVENT_SOURCE_ALREADY_CLOSED());
                case OPEN:
                    throw new IllegalStateException(LocalizationMessages.EVENT_SOURCE_ALREADY_CONNECTED());
            }
        }

        final EventProcessor processor = new EventProcessor(reconnectDelay, reconnectTimeUnit, null);
        executor.submit(processor);

        // return only after the first request to the SSE endpoint has been made
        processor.awaitFirstContact();
    }

    @Override
    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    @Override
    public boolean close(final long timeout, final TimeUnit unit) {
        publisher.close();
        shutdown();

        try {
            if (!executor.awaitTermination(timeout, unit)) {
                LOGGER.log(CONNECTION_ERROR_LEVEL,
                        LocalizationMessages.EVENT_SOURCE_SHUTDOWN_TIMEOUT(endpoint.getUri().toString()));
                return false;
            }
        } catch (final InterruptedException e) {
            LOGGER.log(CONNECTION_ERROR_LEVEL,
                    LocalizationMessages.EVENT_SOURCE_SHUTDOWN_INTERRUPTED(endpoint.getUri().toString()));
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    private void shutdown() {
        if (state.getAndSet(State.CLOSED) != State.CLOSED) {
            // shut down only if has not been shut down before
            executor.shutdownNow();
        }
    }

    /**
     * Private event processor task responsible for connecting to the SSE stream and processing
     * incoming SSE events as well as handling any connection issues.
     */
    private class EventProcessor implements Runnable, EventListener {

        /**
         * Open connection response arrival synchronization latch.
         */
        private final CountDownLatch firstContactSignal;
        /**
         * Last event id.
         */
        private String lastEventId;
        /**
         * Reconnect delay amount.
         */
        private long reconnectDelay;
        /**
         * Reconnect delay time unit.
         */
        private final TimeUnit reconnectTimeUnit;

        EventProcessor(final long reconnectDelay, final TimeUnit reconnectTimeUnit, final String lastEventId) {
            this.firstContactSignal = new CountDownLatch(1);

            this.reconnectDelay = reconnectDelay;
            this.reconnectTimeUnit = reconnectTimeUnit;
            this.lastEventId = lastEventId;
        }

        private EventProcessor(final EventProcessor that) {
            this.firstContactSignal = null;

            this.reconnectDelay = that.reconnectDelay;
            this.reconnectTimeUnit = that.reconnectTimeUnit;
            this.lastEventId = that.lastEventId;
        }

        @Override
        public void run() {
            LOGGER.fine("Listener task started");

            EventInput eventInput = null;
            try {
                try {
                    final Invocation.Builder request = prepareHandshakeRequest();
                    if (state.get() == State.OPEN) {
                        eventInput = request.get(EventInput.class);
                    }
                } finally {
                    if (firstContactSignal != null) {
                        firstContactSignal.countDown();
                    }
                }

                final Thread execThread = Thread.currentThread();

                while (state.get() == State.OPEN && !execThread.isInterrupted()) {
                    if (eventInput == null || eventInput.isClosed()) {
                        LOGGER.fine(String.format("Connection lost - scheduling reconnect in %s %s",
                                reconnectDelay, reconnectTimeUnit));
                        scheduleReconnect(reconnectDelay, reconnectTimeUnit);
                        break;
                    } else {
                        this.onEvent(eventInput.read());
                    }
                }
            } catch (final ServiceUnavailableException ex) {
                LOGGER.fine("Received HTTP 503");
                long delay = reconnectDelay;
                TimeUnit timeUnit = reconnectTimeUnit;
                if (ex.hasRetryAfter()) {
                    LOGGER.fine("Recovering from HTTP 503 using HTTP Retry-After header value as a reconnect delay.");
                    final Date requestTime = new Date();
                    delay = ex.getRetryTime(requestTime).getTime() - requestTime.getTime();
                    delay = (delay > 0) ? delay : 0;
                    timeUnit = TimeUnit.MILLISECONDS;
                }

                LOGGER.fine(String.format("Recovering from HTTP 503 - scheduling to reconnect in %s %s", delay, timeUnit));
                scheduleReconnect(delay, timeUnit);
            } catch (final Exception ex) {
                if (LOGGER.isLoggable(CONNECTION_ERROR_LEVEL)) {
                    LOGGER.log(CONNECTION_ERROR_LEVEL, String.format("Unable to connect - closing the event source to %s.",
                            endpoint.getUri().toASCIIString()), ex);
                }
                // if we're here, an unrecoverable error has occurred - just turn off the lights...
                JerseySseEventSource.this.shutdown();
            } finally {
                if (eventInput != null && !eventInput.isClosed()) {
                    eventInput.close();
                }
                LOGGER.fine("Listener task finished.");
            }
        }

        /**
         * Schedule a new event processor task to reconnect after the specified {@code delay} [milliseconds].
         * <p>
         * If the {@code delay} is zero or negative, the new reconnect task will be scheduled immediately.
         * The {@code reconnectDelay} and {@code lastEventId} field values are propagated into the newly
         * scheduled task.
         * <p>
         * The method will silently abort in case the event source is not {@link EventSource#isOpen() open}.
         * </p>
         *
         * @param reconnectDelay    specifies the amount of time in [reconnectTimeUnits] to wait before attempting a reconnect.
         *                          If zero or negative, the new reconnect task will be scheduled immediately.
         * @param reconnectTimeUnit specifies the time unit for the {@code reconnectDelay} parameter
         */
        private void scheduleReconnect(final long reconnectDelay, final TimeUnit reconnectTimeUnit) {
            final State s = state.get();
            if (s != State.OPEN) {
                LOGGER.fine(String.format("Aborting reconnect of event source in %s state", state));
                return;
            }

            final EventProcessor processor = new EventProcessor(this);
            if (reconnectDelay > 0) {
                executor.schedule(processor, reconnectDelay, reconnectTimeUnit);
            } else {
                executor.submit(processor);
            }
        }

        private Invocation.Builder prepareHandshakeRequest() {
            final Invocation.Builder request = endpoint.request(MediaType.SERVER_SENT_EVENTS);
            if (lastEventId != null && !lastEventId.isEmpty()) {
                request.header(LAST_EVENT_ID_HEADER, lastEventId);
            }
            request.header("Connection", "close");
            return request;
        }


        /**
         * Called by the event source when an inbound event is received.
         * <p>
         * This listener aggregator method is responsible for invoking {@link EventSource#onEvent(InboundEvent)}
         * method on the owning event source as well as for notifying all registered {@link EventListener event listeners}.
         *
         * @param inboundEvent incoming {@link InboundEvent inbound event}.
         */
        @Override
        public void onEvent(final InboundEvent inboundEvent) {
            if (inboundEvent == null) {
                return;
            }

            LOGGER.fine("New event received: " + inboundEvent);

            if (inboundEvent.getId() != null) {
                lastEventId = inboundEvent.getId();
            }
            if (inboundEvent.isReconnectDelaySet()) {
                reconnectDelay = inboundEvent.getReconnectDelay();
            }

            publisher.submit(inboundEvent);
        }

        void awaitFirstContact() {
            LOGGER.fine("Awaiting first contact signal.");
            try {
                if (firstContactSignal == null) {
                    return;
                }

                try {
                    firstContactSignal.await();
                } catch (final InterruptedException ex) {
                    LOGGER.log(CONNECTION_ERROR_LEVEL, LocalizationMessages.EVENT_SOURCE_OPEN_CONNECTION_INTERRUPTED(), ex);
                    Thread.currentThread().interrupt();
                }
            } finally {
                LOGGER.fine("First contact signal released.");
            }
        }
    }

}
