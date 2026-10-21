package com.medizon.service;

import com.medizon.domain.Coupon;
import com.medizon.domain.ReturnSettings;
import com.medizon.dto.PromoForm;
import com.medizon.repository.CouponRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.ReturnSettingsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminSettingsTest {

    @Test
    void returnSettingsMustMakeSense() {
        ReturnSettingsRepository repo = mock(ReturnSettingsRepository.class);
        when(repo.findById(ReturnSettings.ID)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        ReturnSettingsService service = new ReturnSettingsService(repo);

        ReturnSettings form = ReturnSettings.defaults();
        form.setPickupMinDays(4);
        form.setPickupMaxDays(2);
        assertThatThrownBy(() -> service.save(form, "admin@medizon.in")).hasMessageContaining("can't be before the earliest");

        form.setPickupMaxDays(5);
        form.setBankRefundDays(45);
        assertThatThrownBy(() -> service.save(form, "admin@medizon.in")).hasMessage("Bank refunds must be 0 to 30 days.");

        form.setBankRefundDays(5);
        ReturnSettings saved = service.save(form, "admin@medizon.in");
        assertThat(saved.getBankRefundDays()).isEqualTo(5);
        assertThat(saved.getUpdatedBy()).isEqualTo("admin@medizon.in");
    }

    @Test
    void promosAreCheckedAndFilledIn() {
        CouponRepository repo = mock(CouponRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.promoTotals(any())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        CouponService service = new CouponService(repo, orders);

        PromoForm f = new PromoForm();
        f.setCode("bad code");
        f.setAmount(BigDecimal.TEN);
        assertThatThrownBy(() -> service.save(f)).hasMessageContaining("3 to 20 letters or digits");

        f.setCode("save10");
        f.setAmount(new BigDecimal("120"));
        assertThatThrownBy(() -> service.save(f)).hasMessage("A percent discount can be at most 100%.");

        when(repo.existsByCodeIgnoreCase("SAVE10")).thenReturn(true);
        f.setAmount(BigDecimal.TEN);
        assertThatThrownBy(() -> service.save(f)).hasMessage("There's already a promo called SAVE10.");

        when(repo.existsByCodeIgnoreCase("SAVE10")).thenReturn(false);
        f.setMaxDiscount(new BigDecimal("100"));
        f.setMinOrder(new BigDecimal("299"));
        f.setUsesPerDay(2);
        Coupon c = service.save(f);
        assertThat(c.getCode()).isEqualTo("SAVE10");
        assertThat(c.getTitle()).isEqualTo("10% off up to ₹100");
        assertThat(c.getDescription()).isEqualTo("On medicines worth ₹299 or more.");
        assertThat(c.getUsesPerDay()).isEqualTo(2);
    }
}
