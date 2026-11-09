package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.CartRepository;
import com.medizon.repository.MedicineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Partial checkout: only lines ticked for checkout are priced, ordered and taken out of the cart; the rest stay. */
class CartSelectionTest {

    private final User user = new User();
    private CartItemRepository lines;
    private CartRepository carts;
    private CartService service;
    private CartItem dolo, azithral;
    private Cart open;

    private static Medicine medicine(long id, String name, String price, boolean rx) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setPrice(new BigDecimal(price));
        m.setActive(true);
        m.setPrescriptionRequired(rx);
        return m;
    }

    private static CartItem line(long id, Medicine m, int qty) {
        CartItem c = new CartItem();
        c.setId(id);
        c.setMedicine(m);
        c.setQuantity(qty);
        return c;
    }

    @BeforeEach
    void setUp() {
        lines = mock(CartItemRepository.class);
        carts = mock(CartRepository.class);
        CatalogService catalog = mock(CatalogService.class);
        service = new CartService(lines, carts, mock(MedicineRepository.class), mock(MemberService.class), catalog, mock(CouponService.class));

        Store store = new Store();
        store.setId(1L);
        Medicine doloMed = medicine(1, "Dolo 650", "30.00", false);
        Medicine azithralMed = medicine(2, "Azithral 500", "120.00", true);
        dolo = line(11, doloMed, 2);             // 2 x 30 = 60
        azithral = line(12, azithralMed, 1);     // 1 x 120 = 120, needs a prescription
        open = new Cart(user);
        dolo.setCart(open);
        azithral.setCart(open);

        Map<Long, Offer> offers = new HashMap<>();
        for (Medicine m : List.of(doloMed, azithralMed)) {
            StoreItem listing = new StoreItem();
            listing.setStore(store);
            listing.setMedicine(m);
            listing.setStock(100);
            offers.put(m.getId(), new Offer(m, listing, null, 100));
        }
        when(catalog.offers(any(), any())).thenReturn(offers);
        when(lines.findByUserOrderByIdAsc(user)).thenAnswer(i -> new ArrayList<>(List.of(dolo, azithral)));
        when(carts.findFirstByUserAndStatusOrderByIdAsc(user, CartStatus.ACTIVE)).thenReturn(Optional.of(open));
        when(carts.findByUserAndStatus(user, CartStatus.ACTIVE)).thenReturn(List.of(open));
        when(carts.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void theCartPageListsEveryLineButTotalsOnlyTheTickedOnes() {
        azithral.setSelected(false);

        CartSummary page = service.summary(user, null);

        assertThat(page.getItems()).containsExactly(dolo, azithral);
        assertThat(page.getSubtotal()).isEqualByComparingTo("60");
        assertThat(page.getSelectedCount()).isEqualTo(1);
        assertThat(page.isAllSelected()).isFalse();
        // The unticked prescription medicine isn't being bought, so no prescription is asked for
        assertThat(page.isPrescriptionRequired()).isFalse();
    }

    @Test
    void checkoutIsPricedWithTheTickedLinesAlone() {
        dolo.setSelected(false);

        CartSummary checkout = service.summary(user, null, null);

        assertThat(checkout.getItems()).containsExactly(azithral);
        assertThat(checkout.getSubtotal()).isEqualByComparingTo("120");
        assertThat(checkout.isPrescriptionRequired()).isTrue();
    }

    @Test
    void nothingTickedMeansNothingToCheckOut() {
        dolo.setSelected(false);
        azithral.setSelected(false);

        assertThat(service.summary(user, null, null).isEmpty()).isTrue();
        CartSummary page = service.summary(user, null);
        assertThat(page.getSubtotal()).isEqualByComparingTo("0");
        assertThat(page.getDeliveryFee()).isEqualByComparingTo("0");
        assertThat(page.getTotal()).isEqualByComparingTo("0");
    }

    @Test
    void checkingOutPartOfTheCartKeepsTheRestInANewOpenCart() {
        Cart closed = service.checkOut(user, List.of(dolo));

        assertThat(closed).isSameAs(open);
        assertThat(open.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
        verify(lines).deleteAll(List.of(dolo));
        // Azithral stays, in a fresh open cart (the orders keep pointing at the closed one)
        assertThat(azithral.getCart()).isNotSameAs(open);
        assertThat(azithral.getCart().getStatus()).isEqualTo(CartStatus.ACTIVE);
        verify(lines).saveAll(List.of(azithral));
        verify(lines, never()).deleteByUser(any());
    }

    @Test
    void checkingOutEverythingLeavesNoOpenCart() {
        service.checkOut(user, List.of(dolo, azithral));

        verify(lines).deleteAll(List.of(dolo, azithral));
        verify(carts, never()).save(any());          // the next medicine added opens a new cart
        assertThat(open.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
    }

}
