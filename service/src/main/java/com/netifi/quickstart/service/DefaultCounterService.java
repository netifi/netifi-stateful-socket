package com.netifi.quickstart.service;

import io.netty.buffer.ByteBuf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class DefaultCounterService implements CounterService {
  private DefaultSessionService sessionService;

  @Autowired
  public DefaultCounterService(DefaultSessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  public Mono<CounterResponse> delta(CounterRequest message, ByteBuf metadata) {
    int i = sessionService.getSessionData(message.getSessionId()).addAndGet(message.getDelta());
    return Mono.just(CounterResponse.newBuilder().setCount(i).build());
  }

  @Override
  public Mono<CounterResponse> currentCount(CounterRequest message, ByteBuf metadata) {
    int i = sessionService.getSessionData(message.getSessionId()).get();
    return Mono.just(CounterResponse.newBuilder().setCount(i).build());
  }
}
