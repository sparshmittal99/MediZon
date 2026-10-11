package com.medizon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MediZonApplication {

    public static void main(String[] args) {
        // Dose reminders are wall-clock times, so the whole app runs in one zone.
        String zone = System.getenv().getOrDefault("MEDIZON_TZ", "Asia/Kolkata");
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        SpringApplication.run(MediZonApplication.class, args);
    }
}
