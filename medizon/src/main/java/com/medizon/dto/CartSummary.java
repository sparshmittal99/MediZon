package com.medizon.dto;

import com.medizon.domain.CartItem;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@AllArgsConstructor
public class CartSummary {
    private final List<CartItem> items;
    private final BigDecimal subtotal;
    private final BigDecimal deliveryFee;
    private final BigDecimal total;
    private final boolean prescriptionRequired;

    public boolean isEmpty() { return items.isEmpty(); }
}
