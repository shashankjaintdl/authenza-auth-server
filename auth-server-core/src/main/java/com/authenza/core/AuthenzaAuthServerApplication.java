package com.authenza.core;

import com.authenza.core.config.SuperAdminClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.authenza.core", "com.authenza.adapter"})
@EnableConfigurationProperties(SuperAdminClientProperties.class)
public class AuthenzaAuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthenzaAuthServerApplication.class, args);
    }
}
