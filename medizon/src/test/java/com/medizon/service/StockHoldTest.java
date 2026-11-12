package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import com.medizon.repository.CheckoutPaymentRepository;
import com.medizon.repository.StockHoldRepository;
import com.medizon.repository.StoreItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Stock set aside while a customer pays online: nobody else can buy it meanwhile, and it runs out on its own. */
class StockHoldTest {

    private final StockHoldRepository holds = mock(StockHoldRepository.class);
    private final CheckoutPaymentRepository payments = mock(CheckoutPaymentRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final StockHoldService service = new StockHoldService(holds, payments, em);

    private final User asha = user(7L), ravi = user(8L);
    private final Store store = new Store();
    private final Medicine dolo = new Medicine();
    private StoreItem lastPack;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "holdMinutes", 10);
        store.setId(1L);
        dolo.setId(3L);
        dolo.setName("Dolo 650");
        dolo.setPrice(new BigDecimal("30.00"));
        lastPack = new StoreItem();
        lastPack.setId(11L);
        lastPack.setStore(store);
        lastPack.setMedicine(dolo);
        lastPack.setStock(1);
        when(em.find(StoreItem.class, 11L)).thenReturn(lastPack);
        when(payments.save(any())).thenAnswer(i -> { CheckoutPayment p = i.getArgument(0); p.setId(99L); return p; });
    }

    @Test
    void stockHeldForSomeoneElseIsntOffered() {
        lastPack.setHeld(1);
        Offer o = new Offer(dolo, lastPack, null, 0);
        assertThat(o.canSell(1)).isFalse();
        assertThat(lastPack.getAvailable()).isZero();
        assertThat(lastPack.getStock()).isEqualTo(1);       // still on the shelf: only held, not sold
    }

    @Test
    void openingAPaymentHoldsTheCartsPacksForTenMinutes() {
        when(holds.findActive(any(), any())).thenReturn(List.of());

        service.open(payment(asha), cart(1));

        verify(holds).deleteByUser(7L);                      // an earlier payment of hers holds nothing any more
        ArgumentCaptor<StockHold> saved = ArgumentCaptor.forClass(StockHold.class);
        verify(holds).save(saved.capture());
        StockHold h = saved.getValue();
        assertThat(h.getStoreItemId()).isEqualTo(11L);
        assertThat(h.getUserId()).isEqualTo(7L);
        assertThat(h.getPaymentId()).isEqualTo(99L);
        assertThat(h.getQuantity()).isEqualTo(1);
        assertThat(h.getExpiresAt()).isBetween(LocalDateTime.now().plusMinutes(9), LocalDateTime.now().plusMinutes(11));
    }

    @Test
    void whenSomeoneElseIsPayingForTheLastPackTheSecondCustomerIsStoppedBeforePaying() {
        when(holds.findActive(any(), any())).thenReturn(List.of(hold(ravi, 1)));

        assertThatThrownBy(() -> service.open(payment(asha), cart(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Dolo 650 is out of stock right now.");
        verify(payments, never()).save(any());               // no payment opened, nothing to pay
        verify(holds, never()).save(any());
    }

    @Test
    void aCustomersOwnHoldNeverCountsAgainstThem() {
        when(holds.findActive(any(), any())).thenReturn(List.of(hold(asha, 1), hold(ravi, 2)));

        assertThat(service.heldByOthers(List.of(11L), 7L)).containsEntry(11L, 2);
        assertThat(service.heldByOthers(List.of(11L), null)).containsEntry(11L, 3);
    }

    @Test
    void theShopShowsHeldStockAsUnavailableToOthersButNotToTheCustomerHoldingIt() {
        StoreItemRepository items = mock(StoreItemRepository.class);
        CurrentUser current = mock(CurrentUser.class);
        DeliveryAreaService areas = mock(DeliveryAreaService.class);
        CatalogService catalog = new CatalogService(null, null, items, areas, holds, current);
        dolo.setActive(true);
        when(items.findOpenFor(any())).thenReturn(List.of(lastPack));
        when(holds.findActive(any(), any())).thenReturn(List.of(hold(asha, 1)));

        assertThat(catalog.offers(Map.of(dolo, 1), null, ravi).get(3L).canSell(1)).isFalse();
        assertThat(catalog.offers(Map.of(dolo, 1), null, asha).get(3L).canSell(1)).isTrue();

        when(current.find()).thenReturn(Optional.of(ravi));   // signed in and browsing
        assertThat(catalog.offers(Map.of(dolo, 1), null).get(3L).stock()).isZero();
    }

    private StockHold hold(User u, int qty) {
        return new StockHold(11L, u.getId(), 50L + u.getId(), qty, LocalDateTime.now().plusMinutes(5));
    }

    private CheckoutPayment payment(User u) {
        CheckoutPayment p = new CheckoutPayment();
        p.setUser(u);
        return p;
    }

    private CartSummary cart(int qty) {
        CartItem line = new CartItem();
        line.setMedicine(dolo);
        line.setQuantity(qty);
        line.setSelected(true);
        line.setOffer(new Offer(dolo, lastPack, null, qty));
        CartSummary cart = mock(CartSummary.class);
        when(cart.getItems()).thenReturn(List.of(line));
        return cart;
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }
}
