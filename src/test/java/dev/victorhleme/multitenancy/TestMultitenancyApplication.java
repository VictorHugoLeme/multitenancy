package dev.victorhleme.multitenancy;

import org.springframework.boot.SpringApplication;

public class TestMultitenancyApplication {

    public static void main(String[] args) {
        SpringApplication.from(MultitenancyApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
