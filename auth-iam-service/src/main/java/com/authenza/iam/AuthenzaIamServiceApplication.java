package com.authenza.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.authenza.iam",
        "com.authenza.adapter"
})
public class AuthenzaIamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthenzaIamServiceApplication.class, args);
    }
}
