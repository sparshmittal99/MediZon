package com.medizon.dto;

import com.medizon.domain.Coupon;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The admin's add / edit promo form; checked in {@link com.medizon.service.CouponService#save}. */
@Data
public class PromoForm {
    private Long id;
    private String code;
    /** Left blank, it's written from the discount: "10% off up to ₹100". */
    private String title;
    private String description;
    private Coupon.Kind kind = Coupon.Kind.PERCENT;
    private BigDecimal amount;
    private BigDecimal maxDiscount;
    private BigDecimal minOrder = BigDecimal.ZERO;
    private int usesPerDay = 1;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validUntil;
    private boolean active = true;

    public static PromoForm from(Coupon c) {
        PromoForm f = new PromoForm();
        f.setId(c.getId());
        f.setCode(c.getCode());
        f.setTitle(c.getTitle());
        f.setDescription(c.getDescription());
        f.setKind(c.getKind());
        f.setAmount(c.getAmount());
        f.setMaxDiscount(c.getMaxDiscount());
        f.setMinOrder(c.getMinOrder());
        f.setUsesPerDay(c.getUsesPerDay());
        f.setValidUntil(c.getValidUntil());
        f.setActive(c.isActive());
        return f;
    }
}
