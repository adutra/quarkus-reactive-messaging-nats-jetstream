package io.quarkiverse.reactive.messaging.nats.jetstream.test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import io.nats.client.api.AckPolicy;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.ReplayPolicy;
import io.quarkiverse.reactive.messaging.nats.NatsConfiguration;
import io.quarkiverse.reactive.messaging.nats.jetstream.JetStreamOutgoingMessageMetadata;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.Connection;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.ConnectionFactory;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.api.StreamState;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.configuration.ConnectionConfiguration;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.configuration.FetchConsumerConfiguration;
import io.quarkiverse.reactive.messaging.nats.jetstream.client.configuration.PublishConfiguration;
import io.smallrye.mutiny.Uni;

@Path("/request-reply")
@Produces("application/json")
@RequestScoped
public class RequestReplyResource {
    private final ConnectionFactory connectionFactory;
    private final NatsConfiguration natsConfiguration;
    private final String streamName;
    private final AtomicReference<Connection> messageConnection;

    @Inject
    public RequestReplyResource(ConnectionFactory connectionFactory,
            NatsConfiguration natsConfiguration) {
        this.connectionFactory = connectionFactory;
        this.natsConfiguration = natsConfiguration;
        this.streamName = "request-reply";
        this.messageConnection = new AtomicReference<>();
    }

    @GET
    @Path("/streams")
    public Uni<List<String>> getStreams() {
        return getOrEstablishMessageConnection().onItem().transformToUni(Connection::getStreams);
    }

    @GET
    @Path("/streams/{stream}/consumers")
    public Uni<List<String>> getConsumers(@PathParam("stream") String stream) {
        return getOrEstablishMessageConnection().onItem()
                .transformToUni(connection -> connection.getConsumerNames(stream));
    }

    @GET
    @Path("/streams/{stream}/subjects")
    public Uni<List<String>> getSubjects(@PathParam("stream") String stream) {
        return getOrEstablishMessageConnection().onItem().transformToUni(connection -> connection.getSubjects(stream));
    }

    @GET
    @Path("/streams/{stream}/state")
    public Uni<StreamState> getStreamState(@PathParam("stream") String stream) {
        return getOrEstablishMessageConnection().onItem()
                .transformToUni(connection -> connection.getStreamState(stream));
    }

    @POST
    @Path("/subjects/{subject}/{id}/{data}")
    public Uni<Void> produceData(@PathParam("subject") String subject, @PathParam("id") String id,
            @PathParam("data") String data) {
        return getOrEstablishMessageConnection()
                .onItem()
                .transformToUni(connection -> produceData(connection, subject, id, data, UUID.randomUUID().toString()));
    }

    @GET
    @Path("/subjects/{subject}")
    public Uni<Data> consumeData(@PathParam("subject") String subject) {
        return getOrEstablishMessageConnection().onItem().transformToUni(connection -> consumeData(connection, subject));
    }

    public void terminate(
            @Observes(notifyObserver = Reception.IF_EXISTS) @Priority(50) @BeforeDestroyed(ApplicationScoped.class) Object ignored) {
        try {
            if (messageConnection.get() != null) {
                messageConnection.get().close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Uni<Connection> getOrEstablishMessageConnection() {
        return Uni.createFrom().item(() -> Optional.ofNullable(messageConnection.get())
                .filter(Connection::isConnected)
                .orElse(null))
                .onItem().ifNull()
                .switchTo(() -> connectionFactory.create(ConnectionConfiguration.of(natsConfiguration), (event, message) -> {
                }))
                .onItem().invoke(this.messageConnection::set);
    }

    private Uni<Void> produceData(Connection connection, String subject, String id, String data, String messageId) {
        return connection.publish(
                Message.of(new Data(data, id, messageId), Metadata.of(JetStreamOutgoingMessageMetadata.of(messageId))),
                new PublishConfiguration() {
                    @Override
                    public boolean traceEnabled() {
                        return true;
                    }

                    @Override
                    public String stream() {
                        return streamName;
                    }

                    @Override
                    public String subject() {
                        return "events." + subject;
                    }
                }, getConsumerConfiguration(streamName, subject))
                .onItem().transformToUni(m -> Uni.createFrom().voidItem());
    }

    public Uni<Data> consumeData(Connection connection, String subject) {
        return connection.nextMessage(getConsumerConfiguration(streamName, subject))
                .map(message -> {
                    message.ack();
                    return message.getPayload();
                })
                .onFailure().recoverWithUni(Uni.createFrom().failure(NotFoundException::new));
    }

    private FetchConsumerConfiguration<Data> getConsumerConfiguration(String streamName, String subject) {
        return new FetchConsumerConfiguration<>() {
            @Override
            public String stream() {
                return streamName;
            }

            @Override
            public Optional<Long> startSequence() {
                return Optional.empty();
            }

            @Override
            public Optional<AckPolicy> ackPolicy() {
                return Optional.empty();
            }

            @Override
            public Optional<ZonedDateTime> pauseUntil() {
                return Optional.empty();
            }

            @Override
            public Optional<String> name() {
                return Optional.of(subject);
            }

            @Override
            public boolean traceEnabled() {
                return true;
            }

            @Override
            public Optional<Class<Data>> payloadType() {
                return Optional.empty();
            }

            @Override
            public boolean exponentialBackoff() {
                return false;
            }

            @Override
            public Duration exponentialBackoffMaxDuration() {
                return null;
            }

            @Override
            public Duration ackTimeout() {
                return Duration.ofSeconds(30);
            }

            @Override
            public Optional<DeliverPolicy> deliverPolicy() {
                return Optional.empty();
            }

            @Override
            public Optional<ZonedDateTime> startTime() {
                return Optional.empty();
            }

            @Override
            public Optional<Long> maxAckPending() {
                return Optional.empty();
            }

            @Override
            public Optional<String> durable() {
                return Optional.of(subject);
            }

            @Override
            public List<String> filterSubjects() {
                return List.of("events." + subject);
            }

            @Override
            public Optional<Duration> ackWait() {
                return Optional.empty();
            }

            @Override
            public Optional<String> description() {
                return Optional.empty();
            }

            @Override
            public Optional<Duration> inactiveThreshold() {
                return Optional.empty();
            }

            @Override
            public Optional<Long> maxDeliver() {
                return Optional.empty();
            }

            @Override
            public Optional<ReplayPolicy> replayPolicy() {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> replicas() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> memoryStorage() {
                return Optional.empty();
            }

            @Override
            public Optional<String> sampleFrequency() {
                return Optional.empty();
            }

            @Override
            public Map<String, String> metadata() {
                return Map.of();
            }

            @Override
            public List<Duration> backoff() {
                return List.of();
            }

            @Override
            public Optional<Duration> fetchTimeout() {
                return Optional.of(Duration.ofSeconds(5));
            }
        };
    }
}
