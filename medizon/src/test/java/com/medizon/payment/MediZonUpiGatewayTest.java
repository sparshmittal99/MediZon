package com.medizon.payment;

import com.medizon.domain.PaymentMethod;
import com.medizon.service.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediZonUpiGatewayTest {

    private final MediZonUpiGateway upi = new MediZonUpiGateway();

    @Test
    void aRequestIsPaidOnlyOnceApproved() {
        String order = upi.createOrder(17600, "MZ-1-1");
        assertThatThrownBy(() -> upi.confirm(order, null, null)).isInstanceOf(BusinessException.class);
        assertThat(upi.paidPayments(order)).isEmpty();

        PaymentGateway.Paid paid = upi.approve(order, "9876543210@ybl");
        assertThat(paid.amountPaise()).isEqualTo(17600);
        assertThat(paid.method()).isEqualTo(PaymentMethod.UPI);
        assertThat(paid.id()).matches("\\d{12}");   // a UTR, as the bank shows it
        // Confirming or approving again gives the same payment, never a second one
        assertThat(upi.confirm(order, null, null)).isEqualTo(paid);
        assertThat(upi.approve(order, "9876543210@ybl")).isEqualTo(paid);
        assertThat(upi.paidPayments(order)).containsExactly(paid);
    }

    @Test
    void paidFromAUpiAppNeedsNoUpiId() {
        String order = upi.createOrder(5000, "MZ-1-2");
        assertThat(upi.approve(order, null).amountPaise()).isEqualTo(5000);
    }

    @Test
    void upiIdsAreCheckedAndFailureIdsAreDeclined() {
        assertThat(upi.verify("9876543210@ybl", "Sparsh Mittal")).isEqualTo("Sparsh Mittal");
        assertThatThrownBy(() -> upi.verify("not-an-id", "Sparsh")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> upi.verify("name@", "Sparsh")).isInstanceOf(BusinessException.class);

        String order = upi.createOrder(5000, "MZ-1-3");
        assertThatThrownBy(() -> upi.approve(order, "failure@okaxis")).hasMessageContaining("declined");
        assertThat(upi.paidPayments(order)).isEmpty();
        assertThatThrownBy(() -> upi.approve("mzupi_unknown", "a@ybl")).hasMessageContaining("expired");
    }

    @Test
    void onlyTakesUpi() {
        assertThat(upi.methods()).containsExactly(PaymentMethod.UPI);
        assertThat(upi.refund("mzupi_1", "123", 100, "test")).startsWith("rfnd_upi_");
    }
}
