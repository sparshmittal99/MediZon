package com.medizon.payment;

import com.medizon.domain.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every payment gateway, and which one takes each online method: Razorpay for what its account takes (cards and net
 * banking until UPI is turned on after KYC), MediZon UPI for UPI otherwise.
 */
@Component
public class PaymentGateways {

    private final Map<Gateway, PaymentGateway> all = new EnumMap<>(Gateway.class);

    public PaymentGateways(List<PaymentGateway> gateways) {
        gateways.forEach(g -> all.put(g.id(), g));
    }

    /** The gateway; payments from before there were several went through Razorpay. */
    public PaymentGateway get(Gateway id) {
        return all.get(id == null ? Gateway.RAZORPAY : id);
    }

    /** The first gateway set up that takes {@code method}, in {@link Gateway} order. */
    public Optional<PaymentGateway> forMethod(PaymentMethod method) {
        return all.values().stream().filter(g -> g.isConfigured() && g.methods().contains(method)).findFirst();
    }

    /** The online methods some gateway takes, in the payment page's order. */
    public List<PaymentMethod> onlineMethods() {
        return Arrays.stream(PaymentMethod.online()).filter(m -> forMethod(m).isPresent()).toList();
    }
}
