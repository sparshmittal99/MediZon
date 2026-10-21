package com.medizon.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pays out return refunds on their day (2 days after pickup for UPI, 7 for bank accounts and cards). */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundScheduler {

    private final ReturnService returnService;

    /** Every hour, and on startup so refunds that fell due while the app was down aren't left waiting. */
    @Scheduled(cron = "0 0 * * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void payDueRefunds() {
        try {
            int paid = returnService.refundDue();
            if (paid > 0) log.info("Paid {} due return refund(s)", paid);
        } catch (Exception e) {
            log.error("Refund run failed", e);
        }
    }
}
