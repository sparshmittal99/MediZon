package com.medizon.dto;

import lombok.Value;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** When an order of a medicine would arrive at a PIN code: "Delivery in 3-5 days, Fri, 17 Oct - Mon, 20 Oct". */
@Value
public class DeliveryEstimate {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);

    int minDays;
    int maxDays;
    LocalDate from;
    LocalDate to;
    /** Time left to order for same-day delivery; null when it isn't same-day. */
    Duration orderWithin;

    public boolean isSameDay() { return maxDays == 0; }

    public String getHeadline() {
        if (isSameDay()) return "Same-day delivery";
        if (minDays == 1 && maxDays == 1) return "Delivery tomorrow";
        if (minDays == maxDays) return "Delivery in " + minDays + " days";
        if (minDays == 0) return "Delivery in up to " + maxDays + (maxDays == 1 ? " day" : " days");
        return "Delivery in " + minDays + "–" + maxDays + " days";
    }

    /** "Today", "Tomorrow, 15 Oct" or "Fri, 17 Oct – Mon, 20 Oct". */
    public String getDates() {
        if (from.equals(to)) return day(from);
        return day(from) + " – " + day(to);
    }

    /** "Order within 2 hrs 15 mins" for same-day delivery. */
    public String getOrderWithinText() {
        if (orderWithin == null) return null;
        long mins = Math.max(1, orderWithin.toMinutes());
        long h = mins / 60, m = mins % 60;
        String text = (h > 0 ? h + (h == 1 ? " hr" : " hrs") : "") + (h > 0 && m > 0 ? " " : "") + (m > 0 ? m + (m == 1 ? " min" : " mins") : "");
        return "Order within " + text;
    }

    private static String day(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.plusDays(1))) return "Tomorrow, " + d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        return d.format(DAY);
    }
}
