package com.medizon.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Sales, orders and returns for one day, or totals for a period when {@code day} is null.
 * Sales are what customers paid (GST and delivery included) on orders that weren't cancelled; refunds count on the
 * day they were paid out, so net revenue for a day is sales minus that day's refunds.
 */
@Getter @Setter
public class SalesDay {
    private final LocalDate day;
    /** Set on month-wise rows; {@code day} is then the 1st of the month. */
    private final YearMonth month;
    private long orders;
    private long cancelled;
    private long packs;
    private BigDecimal sales = BigDecimal.ZERO;
    /** Round off on those orders: already inside {@code sales}, shown apart like a "Round off" ledger. */
    private BigDecimal roundOff = BigDecimal.ZERO;
    private long returns;
    private long refundCount;
    private BigDecimal refunds = BigDecimal.ZERO;

    public SalesDay(LocalDate day) { this.day = day; this.month = null; }

    public SalesDay(YearMonth month) { this.day = month.atDay(1); this.month = month; }

    public boolean isMonthly() { return month != null; }

    /** Orders that went ahead (not cancelled). */
    public long getCompletedOrders() { return orders - cancelled; }

    public BigDecimal getNet() { return sales.subtract(refunds); }

    public BigDecimal getAverageOrder() {
        long n = getCompletedOrders();
        return n == 0 ? BigDecimal.ZERO : sales.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP);
    }

    public boolean isEmpty() { return orders == 0 && returns == 0 && refundCount == 0; }

    public void add(SalesDay d) {
        orders += d.orders;
        cancelled += d.cancelled;
        packs += d.packs;
        sales = sales.add(d.sales);
        roundOff = roundOff.add(d.roundOff);
        returns += d.returns;
        refundCount += d.refundCount;
        refunds = refunds.add(d.refunds);
    }

    public static SalesDay total(Iterable<SalesDay> days) {
        SalesDay t = new SalesDay((LocalDate) null);
        days.forEach(t::add);
        return t;
    }
}
