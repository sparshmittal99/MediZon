package com.medizon.dto;

import com.medizon.domain.CartItem;
import com.medizon.domain.RoundOff;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
public class CartSummary {
    private final List<CartItem> items;
    private final BigDecimal subtotal;
    private final BigDecimal deliveryFee;
    /** Paise added or taken off so the bill is in whole rupees, as the order will be. */
    private final BigDecimal roundOff;
    private final BigDecimal total;
    private final boolean prescriptionRequired;

    public CartSummary(List<CartItem> items, BigDecimal subtotal, BigDecimal deliveryFee, boolean prescriptionRequired) {
        this.items = items;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        BigDecimal exact = subtotal.add(deliveryFee);
        this.roundOff = RoundOff.of(exact);
        this.total = exact.add(roundOff);
        this.prescriptionRequired = prescriptionRequired;
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public String getRoundOffLabel() { return RoundOff.label(roundOff); }
}
