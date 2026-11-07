package com.medizon.service;

import com.medizon.domain.DailyQuote;
import com.medizon.repository.DailyQuoteRepository;
import com.medizon.repository.NotificationDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * One health quote a day, as a phone notification on customers' Android apps only: nothing in the notification centre,
 * no email. A different quote every day ({@link HealthQuotes}), at a different time between 7 am and 9 pm (the
 * server's time zone, India time). Each day is recorded once sent ({@link DailyQuote}), so it's never sent twice.
 * {@code DAILY_HEALTH_QUOTE=false} turns it off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyHealthQuoteService {

    private final PushService phones;
    private final NotificationDeviceRepository devices;
    private final DailyQuoteRepository sent;

    @Value("${medizon.push.daily-quote:true}")
    private boolean enabled;

    /** Every minute: once today's time has come (and it's not yet 9 pm), today's quote goes out if it hasn't already. */
    @Scheduled(cron = "0 * * * * *")
    public void sendIfDue() {
        if (!enabled || !phones.isEnabled()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();
            LocalTime time = now.toLocalTime();
            if (time.isBefore(HealthQuotes.sendTime(today)) || !time.isBefore(HealthQuotes.LATEST)) return;
            if (sent.existsById(today)) return;
            send(today);
        } catch (Exception e) {
            log.warn("Daily health quote failed: {}", e.getMessage());
        }
    }

    private void send(LocalDate day) {
        String quote = HealthQuotes.forDay(day);
        // Recorded before sending, so a crash half way can't send it twice
        DailyQuote record = sent.save(new DailyQuote(day, quote));
        List<String> tokens = devices.findCustomerPushTokens();
        Map<String, String> data = Map.of(
                "nid", "quote-" + day,          // one per day on the phone
                "type", "HEALTH_QUOTE",
                "title", "🌿 Today's health thought",
                "body", quote,
                "link", "/");
        tokens.forEach(t -> phones.send(t, data));
        record.setPhones(tokens.size());
        sent.save(record);
        log.info("Daily health quote sent to {} phone(s): {}", tokens.size(), quote);
    }
}
