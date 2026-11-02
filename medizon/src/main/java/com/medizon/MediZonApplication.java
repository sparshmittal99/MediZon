package com.medizon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MediZonApplication {

    public static void main(String[] args) {
        loadDotEnv();
        // Dose reminders are wall-clock times, so the whole app runs in one zone.
        String zone = System.getenv().getOrDefault("MEDIZON_TZ", "Asia/Kolkata");
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        SpringApplication.run(MediZonApplication.class, args);
    }

    /**
     * Loads a local .env file if present so developers can configure credentials (email, DB, API keys)
     * without setting system-wide environment variables or committing secrets to git.
     */
    private static void loadDotEnv() {
        for (Path path : List.of(Path.of(".env"), Path.of("../.env"), Path.of("medizon/.env"))) {
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        int idx = trimmed.indexOf('=');
                        if (idx > 0) {
                            String key = trimmed.substring(0, idx).trim();
                            String val = trimmed.substring(idx + 1).trim();
                            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                                val = val.substring(1, val.length() - 1);
                            }
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, val);
                            }
                        }
                    }
                    break;
                } catch (Exception ignored) {
                }
            }
        }
    }
}
