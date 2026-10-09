package com.example.aikb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiKnowledgeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiKnowledgeAssistantApplication.class, args);
    }
}
