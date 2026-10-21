package com.medizon.service;

import com.medizon.domain.ReturnSettings;
import com.medizon.repository.ReturnSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** The admin's return settings; the defaults until they're first saved. */
@Service
@RequiredArgsConstructor
public class ReturnSettingsService {

    private final ReturnSettingsRepository settings;

    public ReturnSettings get() {
        return settings.findById(ReturnSettings.ID).orElseGet(ReturnSettings::defaults);
    }

    @Transactional
    public ReturnSettings save(ReturnSettings form, String by) {
        within("The return window", form.getReturnWindowDays(), 1, 60);
        within("The earliest pickup", form.getPickupMinDays(), 0, 14);
        within("The latest pickup", form.getPickupMaxDays(), 0, 14);
        if (form.getPickupMaxDays() < form.getPickupMinDays()) {
            throw new BusinessException("The latest pickup day can't be before the earliest.");
        }
        within("UPI refunds", form.getUpiRefundDays(), 0, 30);
        within("Bank refunds", form.getBankRefundDays(), 0, 30);
        within("Card refunds", form.getCardRefundDays(), 0, 30);
        ReturnSettings s = settings.findById(ReturnSettings.ID).orElseGet(ReturnSettings::defaults);
        s.setReturnWindowDays(form.getReturnWindowDays());
        s.setPickupMinDays(form.getPickupMinDays());
        s.setPickupMaxDays(form.getPickupMaxDays());
        s.setUpiRefundDays(form.getUpiRefundDays());
        s.setBankRefundDays(form.getBankRefundDays());
        s.setCardRefundDays(form.getCardRefundDays());
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(by);
        return settings.save(s);
    }

    private static void within(String what, int value, int min, int max) {
        if (value < min || value > max) throw new BusinessException(what + " must be " + min + " to " + max + " days.");
    }
}
