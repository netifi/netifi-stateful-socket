package com.netifi.quickstart.service;

import com.netifi.common.tags.Tags;
import com.netifi.spring.core.BrokerClientTagSupplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Main {

  public static void main(String... args) {
    SpringApplication.run(Main.class, args);
  }
  
}
