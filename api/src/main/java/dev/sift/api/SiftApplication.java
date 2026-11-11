package dev.sift.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the web server. Spring finds the other classes in this package by itself. */
@SpringBootApplication
public class SiftApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiftApplication.class, args);
    }
}
