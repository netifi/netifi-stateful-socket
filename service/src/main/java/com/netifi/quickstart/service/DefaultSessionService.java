package com.netifi.quickstart.service;

import com.google.protobuf.Empty;
import com.netifi.broker.BrokerClient;
import com.netifi.broker.info.BrokerInfoServiceClient;
import com.netifi.broker.info.Destination;
import com.netifi.broker.info.Event;
import com.netifi.common.tags.Tags;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

@Component
public class DefaultSessionService implements SessionService {
  private static final Logger logger = LoggerFactory.getLogger(DefaultSessionService.class);
  private static final String DESTINATION_TAG = "com.netifi.destination";
  private final BrokerClient brokerClient;
  private final SessionResponse sessionResponse;
  private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
  private final Map<String, Disposable> sessionTrackers = new ConcurrentHashMap<>();
  private final BrokerInfoServiceClient brokerInfoServiceClient;

  @Autowired
  public DefaultSessionService(BrokerClient brokerClient) {
    this.brokerClient = brokerClient;
    this.sessionResponse =
        SessionResponse.newBuilder().setDestination(brokerClient.getDestination()).build();
    this.brokerInfoServiceClient =
        new BrokerInfoServiceClient(
            brokerClient.groupServiceSocket("com.netifi.broker.brokerServices", Tags.empty()));
  }

  private void doTrackSession(String group, String destination, String sessionId) {
    sessionTrackers.computeIfAbsent(
        sessionId,
        s ->
            brokerInfoServiceClient
                .streamDestinationEventsByDestinationName(
                    Destination.newBuilder()
                        .setGroup(group)
                        .addTags(
                            com.netifi.broker.info.Tag.newBuilder()
                                .setKey(DESTINATION_TAG)
                                .setValue(destination)
                                .build())
                        .build())
                .filter(event -> event.getType() == Event.Type.LEAVE)
                .doOnEach(
                    signalType -> {
                      SignalType type = signalType.getType();
                      switch (type) {
                        case ON_NEXT:
                        case AFTER_TERMINATE:
                          AtomicInteger remove = counters.remove(sessionId);
                          if (remove != null) {
                            logger.info(
                                "destination event stream received {} signal - cleaning up session for session id {}",
                                type,
                                sessionId);
                          }
                          break;
                        default:
                      }
                    })
                .subscribe());
  }

  @Override
  public Mono<SessionResponse> startSession(SessionRequest message, ByteBuf metadata) {
    String sessionId = message.getSessionId();
    counters.computeIfAbsent(
        sessionId,
        s -> {
          logger.info(
              "starting session for session id {} from group {} and destination {}",
              sessionId,
              message.getSessionGroup(),
              message.getSessionDestination());
          AtomicInteger integer = new AtomicInteger();
          doTrackSession(message.getSessionGroup(), message.getSessionDestination(), sessionId);
          return integer;
        });
    return Mono.just(sessionResponse);
  }

  @Override
  public Mono<Empty> endSession(SessionRequest message, ByteBuf metadata) {
    return Mono.fromSupplier(
        () -> {
          String sessionId = message.getSessionId();

          logger.info("ending session for session id {}", sessionId);

          Disposable disposable = sessionTrackers.remove(sessionId);
          if (disposable != null) {
            disposable.dispose();
          }

          return Empty.getDefaultInstance();
        });
  }

  public AtomicInteger getSessionData(String s) {
    return counters.get(s);
  }
}
