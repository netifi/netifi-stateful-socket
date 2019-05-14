package com.netifi.quickstart.client;

import com.netifi.broker.BrokerClient;
import com.netifi.quickstart.service.CounterRequest;
import com.netifi.quickstart.service.CounterResponse;
import com.netifi.quickstart.service.CounterServiceClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Calls the Hello Service */
@Component
public class ClientRunner implements CommandLineRunner {
  private static final Logger logger = LogManager.getLogger(ClientRunner.class);

  @Autowired private BrokerClient brokerClient;

  @Override
  public void run(String... args) throws Exception {
    StatefulSocket s1 =
      StatefulSocket.newInstance(brokerClient, "quickstart.services.sessionDemo");
    
    StatefulSocket s2 =
      StatefulSocket.newInstance(brokerClient, "quickstart.services.sessionDemo");
  
    CounterServiceClient c1 = new CounterServiceClient(s1);
  
    CounterServiceClient c2 = new CounterServiceClient(s2);
  
    c1
        .delta(
            CounterRequest.newBuilder()
                .setDelta(1)
                .setSessionId(s1.getSessionId())
                .build())
        .block();
  
    c2
      .delta(
        CounterRequest.newBuilder()
          .setDelta(10)
          .setSessionId(s2.getSessionId())
          .build())
      .block();
  
    c2
      .delta(
        CounterRequest.newBuilder()
          .setDelta(-5)
          .setSessionId(s2.getSessionId())
          .build())
      .block();
  
  
    c1
        .delta(
            CounterRequest.newBuilder()
                .setDelta(1)
                .setSessionId(s1.getSessionId())
                .build())
        .block();
    
    c1
        .delta(
            CounterRequest.newBuilder()
                .setDelta(1)
                .setSessionId(s1.getSessionId())
                .build())
        .block();

    CounterResponse b1 =
        c1
            .currentCount(
                CounterRequest.newBuilder().setSessionId(s1.getSessionId()).build())
            .block();
  
    CounterResponse b2 =
      c2
        .currentCount(
          CounterRequest.newBuilder().setSessionId(s2.getSessionId()).build())
        .block();
  
    System.out.println("The count for c1 is " + b1.getCount());
  
    System.out.println("The count for c2 is " + b2.getCount());
    
    System.exit(0);
  }
}
