package com.netifi.quickstart.service;

import com.netifi.common.tags.Tags;
import com.netifi.spring.core.BrokerClientTagSupplier;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {
  private static final String HOST_ID_TAG = "com.netifi.host.id";
  public static final String HOST_ID = UUID.randomUUID().toString();

  @Bean
  public BrokerClientTagSupplier brokerClientTagSupplier() {
    return () -> Tags.of(HOST_ID_TAG, HOST_ID);
  }
}
