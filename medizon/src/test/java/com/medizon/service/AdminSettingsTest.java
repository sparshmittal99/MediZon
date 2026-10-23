package com.medizon.service;

import com.medizon.domain.Coupon;
import com.medizon.domain.OrderSettings;
import com.medizon.domain.ReturnSettings;
import com.medizon.dto.PromoForm;
import com.medizon.repository.CouponRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.OrderSettingsRepository;
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
    void theMinimumOrderValueStartsAt99AndStopsSmallerCarts() {
        OrderSettingsRepository repo = mock(OrderSettingsRepository.class);
        when(repo.findById(OrderSettings.ID)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        OrderSettingsService service = new OrderSettingsService(repo);

        assertThat(service.minOrderValue()).isEqualByComparingTo("99");
        assertThatThrownBy(() -> service.requireMinimum(new BigDecimal("59.50")))
                .hasMessage("The minimum order value is ₹99. Add medicines worth ₹39.50 more to place this order.");
        service.requireMinimum(new BigDecimal("99"));

        assertThat(OrderSettingsService.shortBy(new BigDecimal("60"), new BigDecimal("99"))).isEqualByComparingTo("39");
        assertThat(OrderSettingsService.shortBy(new BigDecimal("150"), new BigDecimal("99"))).isEqualByComparingTo("0");
        assertThat(OrderSettingsService.shortBy(new BigDecimal("10"), BigDecimal.ZERO)).isEqualByComparingTo("0");

        assertThatThrownBy(() -> service.save(new BigDecimal("-1"), "admin@medizon.in")).hasMessageContaining("₹0 to ₹10000");
        assertThatThrownBy(() -> service.save(null, "admin@medizon.in")).hasMessageContaining("0 for none");
        OrderSettings saved = service.save(new BigDecimal("149.5"), "admin@medizon.in");
        assertThat(saved.getMinOrderValue()).isEqualByComparingTo("149.50");
        assertThat(saved.getUpdatedBy()).isEqualTo("admin@medizon.in");
        assertThat(OrderSettingsService.plain(saved.getMinOrderValue())).isEqualTo("149.50");
        assertThat(OrderSettingsService.plain(new BigDecimal("99.00"))).isEqualTo("99");
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
