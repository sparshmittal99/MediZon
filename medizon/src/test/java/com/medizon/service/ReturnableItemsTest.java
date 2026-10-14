package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReturnableItemsTest {

    private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final ReturnService service = new ReturnService(returns, orders, mock(MedicationPlanService.class),
            mock(FileStorageService.class), mock(NotificationService.class), mock(UserRepository.class));

    private static OrderItem item(long id, String name, Boolean returnableAtOrder, boolean medicineReturnable) {
        Medicine m = new Medicine();
        m.setName(name);
        m.setReturnable(medicineReturnable);
        OrderItem i = new OrderItem();
        i.setId(id);
        i.setMedicine(m);
        i.setMedicineName(name);
        i.setQuantity(2);
        i.setUnitPrice(new BigDecimal("50"));
        i.setReturnableAtOrder(returnableAtOrder);
        return i;
    }

    private static Order delivered(OrderItem... items) {
        Order o = new Order();
        o.setId(1L);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(LocalDateTime.now().minusDays(1));
        for (OrderItem i : items) o.addItem(i);
        return o;
    }

    @Test
    void onlyNonReturnableItemsMeansNoReturnOption() {
        Order o = delivered(item(1, "Insulin pen", false, false));
        assertThat(service.hasReturnableItems(o)).isFalse();
        assertThat(service.blocker(o)).contains("The items in this order can't be returned.");
    }

    @Test
    void mixedOrderOffersOnlyTheReturnableLines() {
        Order o = delivered(item(1, "Paracetamol", true, true), item(2, "Insulin pen", false, false));
        assertThat(service.hasReturnableItems(o)).isTrue();
        assertThat(service.blocker(o)).isEmpty();
        assertThat(service.returnable(o)).containsOnlyKeys(1L).containsEntry(1L, 2);
    }

    @Test
    void returnabilityIsWhatItWasWhenOrdered() {
        // Made non-returnable in the catalog after the order: the order keeps its promise
        assertThat(item(1, "Paracetamol", true, false).isReturnable()).isTrue();
        // Orders from before the setting existed follow the catalog
        assertThat(item(1, "Paracetamol", null, false).isReturnable()).isFalse();
        assertThat(item(1, "Paracetamol", null, true).isReturnable()).isTrue();
    }

    @Test
    void nonReturnableLinesCantBeRequested() {
        Order o = delivered(item(1, "Paracetamol", true, true), item(2, "Insulin pen", false, false));
        when(orders.getForUser(eq(1L), any())).thenReturn(o);
        assertThatThrownBy(() -> service.request(new User(), 1L, List.of(2L), List.of(1), ReturnReason.values()[0],
                "Damaged", List.of(new MockMultipartFile("images", "pack.jpg", "image/jpeg", new byte[]{1}))))
                .hasMessage("Insulin pen can't be returned.");
    }
}
