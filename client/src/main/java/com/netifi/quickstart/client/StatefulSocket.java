package com.netifi.quickstart.client;

import com.netifi.broker.BrokerClient;
import com.netifi.broker.info.BrokerInfoServiceClient;
import com.netifi.broker.info.Destination;
import com.netifi.broker.info.Event;
import com.netifi.broker.rsocket.BrokerSocket;
import com.netifi.common.tags.Tag;
import com.netifi.common.tags.Tags;
import com.netifi.quickstart.service.SessionRequest;
import com.netifi.quickstart.service.SessionServiceClient;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.SignalType;

public class StatefulSocket extends AbstractRSocket implements BrokerSocket {
  private static final Logger logger = LoggerFactory.getLogger(StatefulSocket.class);
  private static final String DESTINATION_TAG = "com.netifi.destination";
  private static final SessionTimeoutSocket SESSION_TIMEOUT_SOCKET = new SessionTimeoutSocket();
  private final IllegalStateException onCloseException;
  private final String sessionId;
  private final BrokerInfoServiceClient brokerInfoServiceClient;
  private final SessionServiceClient sessionServiceClient;
  private final MonoProcessor<RSocket> nextSocket;
  private final String group;
  private final Tags tags;

  private StatefulSocket(
      String sessionId,
      Duration sessionCreateTimeout,
      BrokerClient brokerClient,
      String group,
      Tags tags,
      BrokerInfoServiceClient brokerInfoServiceClient,
      SessionServiceClient sessionServiceClient) {
    this.sessionId = sessionId;
    this.group = group;
    this.tags = tags;
    this.brokerInfoServiceClient = brokerInfoServiceClient;
    this.sessionServiceClient = sessionServiceClient;
    this.nextSocket = MonoProcessor.create();
    this.onCloseException =
        new IllegalStateException("StatefulSocket with session id " + sessionId + " is closed");

    logger.info("creating new session with session id {}", sessionId);

    Composite composite = Disposables.composite();

    Disposable disposable =
        sessionServiceClient
            .startSession(
                SessionRequest.newBuilder()
                    .setSessionDestination(brokerClient.getDestination())
                    .setSessionGroup(brokerClient.getGroup())
                    .setSessionId(sessionId)
                    .build())
            .timeout(sessionCreateTimeout)
            .subscribe(
                sessionResponse -> {
                  logger.info(
                      "created session to group {} and destination {} with session id {}",
                      group,
                      sessionResponse.getDestination(),
                      sessionId);

                  BrokerSocket brokerSocket =
                      brokerClient.groupServiceSocket(
                          group,
                          tags.and(Tag.of(DESTINATION_TAG, sessionResponse.getDestination())));

                  nextSocket.onNext(brokerSocket);

                  Disposable d =
                      brokerInfoServiceClient
                          .streamDestinationEventsByDestinationName(
                              Destination.newBuilder()
                                  .setGroup(group)
                                  .addTags(
                                      com.netifi.broker.info.Tag.newBuilder()
                                          .setKey(DESTINATION_TAG)
                                          .setValue(sessionResponse.getDestination())
                                          .build())
                                  .build())
                          .filter(
                              event ->
                                  event.getType() == Event.Type.LEAVE
                                      && !StatefulSocket.this.isDisposed())
                          .doOnEach(
                              signalType -> {
                                SignalType type = signalType.getType();
                                switch (type) {
                                  case ON_NEXT:
                                  case AFTER_TERMINATE:
                                    if (!StatefulSocket.this.isDisposed()) {
                                      logger.info(
                                          "destination event stream received {} signal - disposing StateSocket for session with session id {}",
                                          type,
                                          sessionId);
                                      StatefulSocket.this.dispose();
                                    }
                                    break;
                                  default:
                                }
                              })
                          .subscribe();

                  composite.add(d);
                },
                throwable -> {
                  logger.info("error creating session for session id " + sessionId, throwable);
                  nextSocket.onNext(SESSION_TIMEOUT_SOCKET);
                  dispose();
                });

    composite.add(disposable);

    onClose()
        .doFinally(
            s -> {
              logger.info("closing session with session id {}", sessionId);
              if (composite.isDisposed()) {
                composite.dispose();
              }
            })
        .subscribe();
  }

  public static StatefulSocket newInstance(BrokerClient brokerClient, String group, Tag... tags) {
    return newInstance(brokerClient, Duration.ofSeconds(5), group, tags);
  }

  public static StatefulSocket newInstance(BrokerClient brokerClient, String group, Tags tags) {
    return newInstance(brokerClient, Duration.ofSeconds(5), group, tags);
  }

  public static StatefulSocket newInstance(
      BrokerClient brokerClient, Duration sessionCreateTimeout, String group, Tag... tags) {
    return newInstance(brokerClient, sessionCreateTimeout, group, Tags.of(tags));
  }

  public static StatefulSocket newInstance(
      BrokerClient brokerClient, Duration sessionCreateTimeout, String group, Tags tags) {
    Objects.requireNonNull(brokerClient);

    String sessionId = UUID.randomUUID().toString();

    BrokerInfoServiceClient brokerInfoServiceClient =
        new BrokerInfoServiceClient(
            brokerClient.groupServiceSocket("com.netifi.broker.brokerServices", Tags.empty()));

    SessionServiceClient sessionServiceClient =
        new SessionServiceClient(brokerClient.groupServiceSocket(group, tags));

    return new StatefulSocket(
        sessionId,
        sessionCreateTimeout,
        brokerClient,
        group,
        tags,
        brokerInfoServiceClient,
        sessionServiceClient);
  }

  private Mono<Void> errorOnCloseVoid() {
    return onClose().then(Mono.error(onCloseException));
  }

  private Mono<Payload> errorOnClosePayload() {
    return onClose().then(Mono.error(onCloseException));
  }

  @Override
  public Mono<Void> fireAndForget(Payload payload) {
    try {
      RSocket rSocket = nextSocket.peek();
      if (rSocket != null) {
        return Mono.first(rSocket.fireAndForget(payload), errorOnCloseVoid());
      } else {
        return Mono.first(nextSocket.flatMap(r -> r.fireAndForget(payload)), errorOnCloseVoid());
      }
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Mono<Payload> requestResponse(Payload payload) {
    try {
      RSocket rSocket = nextSocket.peek();
      if (rSocket != null) {
        return Mono.first(rSocket.requestResponse(payload), errorOnClosePayload());
      } else {
        return nextSocket.flatMap(r -> r.requestResponse(payload));
      }
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Flux<Payload> requestStream(Payload payload) {
    try {
      RSocket rSocket = nextSocket.peek();
      if (rSocket != null) {
        return Flux.first(rSocket.requestStream(payload), errorOnClosePayload());
      } else {
        return Flux.first(
            nextSocket.flatMapMany(r -> r.requestStream(payload)), errorOnClosePayload());
      }
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
    try {
      RSocket rSocket = nextSocket.peek();
      if (rSocket != null) {
        return Flux.first(rSocket.requestChannel(payloads), errorOnClosePayload());
      } else {
        return Flux.first(
            nextSocket.flatMapMany(r -> r.requestChannel(payloads)), errorOnClosePayload());
      }
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Mono<Void> metadataPush(Payload payload) {
    try {
      RSocket rSocket = nextSocket.peek();
      if (rSocket != null) {
        return Mono.first(rSocket.fireAndForget(payload), errorOnCloseVoid());
      } else {
        return Mono.first(nextSocket.flatMap(r -> r.fireAndForget(payload)), errorOnCloseVoid());
      }
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  public String getSessionId() {
    return sessionId;
  }

  @Override
  public double availability() {
    return isDisposed() ? 1 : 0;
  }

  private static class SessionTimeoutSocket implements RSocket {
    private static final TimeoutException TIMEOUT_EXCEPTION =
        new TimeoutException("timed out creating a session");

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
      return Mono.error(TIMEOUT_EXCEPTION);
    }

    @Override
    public Mono<Payload> requestResponse(Payload payload) {
      return Mono.error(TIMEOUT_EXCEPTION);
    }

    @Override
    public Flux<Payload> requestStream(Payload payload) {
      return Flux.error(TIMEOUT_EXCEPTION);
    }

    @Override
    public Flux<Payload> requestChannel(Publisher<Payload> publisher) {
      return Flux.error(TIMEOUT_EXCEPTION);
    }

    @Override
    public Mono<Void> metadataPush(Payload payload) {
      return Mono.error(TIMEOUT_EXCEPTION);
    }

    @Override
    public Mono<Void> onClose() {
      return Mono.empty();
    }

    @Override
    public void dispose() {}

    @Override
    public double availability() {
      return 0;
    }
  }
}
