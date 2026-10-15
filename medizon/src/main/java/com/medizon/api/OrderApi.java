package com.medizon.api;

import com.medizon.domain.Order;
import com.medizon.domain.OrderItem;
import com.medizon.dto.Invoice;
import com.medizon.service.CurrentUser;
import com.medizon.service.InvoiceService;
import com.medizon.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The signed-in customer's orders and tax invoices as JSON. Another account's order answers 404, the same as one
 * that doesn't exist.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderApi {

    private final OrderService orderService;
    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    public record OrderSummary(Long id, String orderNumber, String status, String statusLabel, LocalDateTime placedAt,
                               int itemCount, BigDecimal total, boolean invoiceAvailable) {
        static OrderSummary of(Order o) {
            return new OrderSummary(o.getId(), o.getOrderNumber(), o.getStatus().name(), o.getStatus().getLabel(),
                    o.getCreatedAt(), o.getItemCount(), o.getTotal(), o.isInvoiceAvailable());
        }
    }

    public record OrderLine(Long id, Long medicineId, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                            String hsnCode, BigDecimal gstRate, String forMembers) {
        static OrderLine of(OrderItem i) {
            return new OrderLine(i.getId(), i.getMedicine() != null ? i.getMedicine().getId() : null, i.getMedicineName(),
                    i.getQuantity(), i.getUnitPrice(), i.getLineTotal(), i.getHsnCode(), i.getGstRate(), i.getMembersLabel());
        }
    }

    public record OrderDetail(Long id, String orderNumber, Long cartId, String status, String statusLabel, String store,
                              String paymentMethod, String paymentStatus, LocalDateTime placedAt, LocalDateTime deliveredAt,
                              String deliverTo, String shippingAddress, String contactPhone, List<OrderLine> items,
                              BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal roundOff, BigDecimal total,
                              boolean cancellable, boolean invoiceAvailable, String invoiceNumber) {
        static OrderDetail of(Order o) {
            return new OrderDetail(o.getId(), o.getOrderNumber(), o.getCart() != null ? o.getCart().getId() : null, o.getStatus().name(), o.getStatus().getLabel(),
                    o.getStore() != null ? o.getStore().getName() : null, o.getPaymentMethod().getLabel(),
                    o.getPaymentStatus().name(), o.getCreatedAt(), o.getDeliveredAt(), o.getDeliverTo(),
                    o.getShippingAddress(), o.getContactPhone(), o.getItems().stream().map(OrderLine::of).toList(),
                    o.getSubtotal(), o.getDeliveryFee(), o.getRoundOff(), o.getTotal(), o.isCancellable(),
                    o.isInvoiceAvailable(), o.getInvoiceNumber());
        }
    }

    /** The invoice without the order entity behind it; the same figures the printable invoice shows. */
    public record InvoiceView(Long orderId, String orderNumber, String invoiceNumber, LocalDateTime invoicedAt,
                              Invoice.Seller seller, String billTo, String billToAddress, String billToGstin,
                              String deliverTo, String shippingAddress, String placeOfSupply, boolean interState,
                              List<Invoice.Line> lines, List<Invoice.TaxRow> taxSummary, BigDecimal taxableTotal,
                              BigDecimal cgstTotal, BigDecimal sgstTotal, BigDecimal igstTotal, BigDecimal taxTotal,
                              BigDecimal invoiceValue, BigDecimal roundOff, BigDecimal grandTotal, String amountInWords) {
        static InvoiceView of(Invoice inv) {
            Order o = inv.getOrder();
            return new InvoiceView(o.getId(), o.getOrderNumber(), o.getInvoiceNumber(), o.getInvoicedAt(), inv.getSeller(),
                    o.getBillTo(), o.getBillToAddress(), o.getBillingGstin(), o.getDeliverTo(), o.getShippingAddress(),
                    inv.getPlaceOfSupply(), inv.isInterState(), inv.getLines(), inv.getTaxSummary(), inv.getTaxableTotal(),
                    inv.getCgstTotal(), inv.getSgstTotal(), inv.getIgstTotal(), inv.getTaxTotal(), inv.getInvoiceValue(),
                    inv.getRoundOff(), inv.getGrandTotal(), inv.getAmountInWords());
        }
    }

    @GetMapping
    public List<OrderSummary> list() {
        return orderService.forUser(currentUser.get()).stream().map(OrderSummary::of).toList();
    }

    @GetMapping("/{id}")
    public OrderDetail detail(@PathVariable Long id) {
        return OrderDetail.of(orderService.getForUser(id, currentUser.get()));
    }

    /** 400 until the order ships, when the invoice is issued. */
    @GetMapping("/{id}/invoice")
    public InvoiceView invoice(@PathVariable Long id) {
        return InvoiceView.of(invoiceService.forOrder(orderService.getForUser(id, currentUser.get())));
    }
}
