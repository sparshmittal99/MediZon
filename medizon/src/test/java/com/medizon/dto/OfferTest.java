package com.medizon.dto;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.domain.StoreItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which store delivers a product to a PIN code, and at what price. */
class OfferTest {

    private final Store bandra = store(1L, "Bandra"), andheri = store(2L, "Andheri"), thane = store(3L, "Thane");
    /** Served by Bandra (same day), then Andheri (1-2 days), then Thane (2-4 days). */
    private final List<StoreArea> pin400050 = List.of(area(bandra, 0, 0), area(andheri, 1, 2), area(thane, 2, 4));

    @Test
    void theNearestStoreWithStockDelivers() {
        Medicine m = medicine(false);
        Offer o = Offer.pick(m, pin400050, Map.of(1L, item(bandra, m, 20, null), 2L, item(andheri, m, 50, null)), 1);
        assertThat(o.store()).isSameAs(bandra);
        assertThat(o.area().getMaxDays()).isZero();
    }

    @Test
    void whenTheNearestStoreHasNoneTheNextNearestDelivers() {
        Medicine m = medicine(false);
        Offer outOfStock = Offer.pick(m, pin400050, Map.of(1L, item(bandra, m, 0, null), 3L, item(thane, m, 9, null)), 1);
        assertThat(outOfStock.store()).isSameAs(thane);

        Offer notCarried = Offer.pick(m, pin400050, Map.of(2L, item(andheri, m, 5, null)), 1);
        assertThat(notCarried.store()).isSameAs(andheri);

        Offer notEnough = Offer.pick(m, pin400050, Map.of(1L, item(bandra, m, 2, null), 2L, item(andheri, m, 10, null)), 5);
        assertThat(notEnough.store()).isSameAs(andheri);
    }

    @Test
    void expiredStockDoesntCount() {
        Medicine m = medicine(false);
        StoreItem expired = item(bandra, m, 30, null);
        expired.setExpiryDate(LocalDate.now().minusDays(1));
        Offer o = Offer.pick(m, pin400050, Map.of(1L, expired, 2L, item(andheri, m, 3, null)), 1);
        assertThat(o.store()).isSameAs(andheri);
    }

    @Test
    void outOfStockEverywhereShowsTheNearestListingAsOutOfStock() {
        Medicine m = medicine(false);
        Offer o = Offer.pick(m, pin400050, Map.of(2L, item(andheri, m, 0, null), 3L, item(thane, m, 0, null)), 1);
        assertThat(o.store()).isSameAs(andheri);
        assertThat(o.canSell(1)).isFalse();
        assertThat(o.stock()).isZero();
    }

    @Test
    void notSoldByAnyStoreServingThePincode() {
        Medicine m = medicine(false);
        Offer o = Offer.pick(m, pin400050, Map.of(), 1);
        assertThat(o.isListed()).isFalse();
        assertThat(o.price()).isEqualByComparingTo("100");
    }

    @Test
    void otcMedicinesSellAtTheStorePricePrescriptionOnesAtTheMasterPrice() {
        Medicine otc = medicine(false), rx = medicine(true);
        assertThat(Offer.pick(otc, pin400050, Map.of(1L, item(bandra, otc, 5, "92.50")), 1).price()).isEqualByComparingTo("92.50");
        assertThat(Offer.pick(otc, pin400050, Map.of(1L, item(bandra, otc, 5, null)), 1).price()).isEqualByComparingTo("100");
        assertThat(Offer.pick(rx, pin400050, Map.of(1L, item(bandra, rx, 5, "80")), 1).price()).isEqualByComparingTo("100");
    }

    @Test
    void withoutALocationTheCheapestStoreWithStockIsShown() {
        Medicine m = medicine(false);
        Offer o = Offer.pick(m, null, Map.of(1L, item(bandra, m, 5, "99"), 2L, item(andheri, m, 0, "90"), 3L, item(thane, m, 5, "95")), 1);
        assertThat(o.store()).isSameAs(thane);
        assertThat(o.area()).isNull();
    }

    @Test
    void closedStoresAndHiddenListingsAreSkipped() {
        Medicine m = medicine(false);
        bandra.setActive(false);
        StoreItem hidden = item(andheri, m, 5, null);
        hidden.setActive(false);
        Offer o = Offer.pick(m, pin400050, Map.of(1L, item(bandra, m, 5, null), 2L, hidden, 3L, item(thane, m, 5, null)), 1);
        assertThat(o.store()).isSameAs(thane);
    }

    private static Store store(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private static StoreArea area(Store s, int min, int max) {
        StoreArea a = new StoreArea();
        a.setStore(s);
        a.setPincode("400050");
        a.setMinDays(min);
        a.setMaxDays(max);
        return a;
    }

    private static Medicine medicine(boolean rx) {
        Medicine m = new Medicine();
        m.setId(rx ? 9L : 8L);
        m.setName(rx ? "Metformin" : "Paracetamol");
        m.setPrice(new BigDecimal("100"));
        m.setMrp(new BigDecimal("120"));
        m.setPrescriptionRequired(rx);
        return m;
    }

    private static StoreItem item(Store s, Medicine m, int stock, String price) {
        StoreItem i = new StoreItem();
        i.setStore(s);
        i.setMedicine(m);
        i.setStock(stock);
        if (price != null) i.setPrice(new BigDecimal(price));
        return i;
    }
}
