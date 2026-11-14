package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreAreaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The shop's category and product lists come from memory until something they're made from changes. */
class CatalogCacheTest {

    private MedicineRepository medicines;
    private StoreAreaRepository areas;
    private DeliveryAreaService deliveryAreas;
    private CatalogCache cache;
    private CatalogService catalog;

    private static Medicine medicine(long id, String name) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setActive(true);
        return m;
    }

    private static StoreArea area(long storeId) {
        Store s = new Store();
        s.setId(storeId);
        StoreArea a = new StoreArea();
        a.setStore(s);
        return a;
    }

    @BeforeEach
    void setUp() {
        medicines = mock(MedicineRepository.class);
        areas = mock(StoreAreaRepository.class);
        deliveryAreas = mock(DeliveryAreaService.class);
        cache = new CatalogCache();
        catalog = new CatalogService(medicines, areas, null, deliveryAreas, null, null, cache);
        when(medicines.findActiveCategories()).thenReturn(List.of("Pain & Fever"));
        when(medicines.sold()).thenReturn(List.of(medicine(1, "Dolo 650")));
    }

    @Test
    void everyPageAfterTheFirstGetsTheCategoriesWithoutAQuery() {
        assertThat(catalog.categories(null)).containsExactly("Pain & Fever");
        assertThat(catalog.categories(null)).containsExactly("Pain & Fever");
        assertThat(catalog.categories("")).containsExactly("Pain & Fever");
        verify(medicines, times(1)).findActiveCategories();
    }

    @Test
    void aCommittedChangeShowsOnTheNextRequest() {
        assertThat(catalog.sold(null)).extracting(Medicine::getName).containsExactly("Dolo 650");
        when(medicines.sold()).thenReturn(List.of(medicine(1, "Dolo 650"), medicine(2, "Crocin")));

        assertThat(catalog.sold(null)).hasSize(1);       // still from memory
        cache.clear();                                    // what CatalogChangeWatcher does after the commit
        assertThat(catalog.sold(null)).extracting(Medicine::getName).containsExactly("Dolo 650", "Crocin");
    }

    @Test
    void eachDeliveryAreaHasItsOwnListAndAreasWithTheSameStoresShareOne() {
        when(deliveryAreas.isListed(any())).thenReturn(true);
        when(areas.serving("110001")).thenReturn(List.of(area(5), area(3)));
        when(areas.serving("110002")).thenReturn(List.of(area(3), area(5)));
        when(areas.serving("400001")).thenReturn(List.of(area(9)));
        when(medicines.soldIn(List.of(3L, 5L))).thenReturn(List.of(medicine(1, "Dolo 650")));
        when(medicines.soldIn(List.of(9L))).thenReturn(List.of(medicine(2, "Crocin")));

        assertThat(catalog.sold("110001")).extracting(Medicine::getName).containsExactly("Dolo 650");
        assertThat(catalog.sold("110002")).extracting(Medicine::getName).containsExactly("Dolo 650");
        assertThat(catalog.sold("400001")).extracting(Medicine::getName).containsExactly("Crocin");
        verify(medicines, times(1)).soldIn(List.of(3L, 5L));
        verify(medicines, never()).sold();
    }

    @Test
    void anUnservedPinCodeShopsFromEveryOpenStore() {
        when(deliveryAreas.isListed("999999")).thenReturn(false);
        assertThat(catalog.sold("999999")).extracting(Medicine::getName).containsExactly("Dolo 650");
        verify(medicines).sold();
    }

    @Test
    void aListReadWhileAChangeWasCommittedIsNotKept() {
        AtomicInteger loads = new AtomicInteger();
        List<String> stale = cache.categories(List.of(), () -> {
            loads.incrementAndGet();
            cache.clear();                                // the change commits while this list is being read
            return List.of("Old name");
        });
        assertThat(stale).containsExactly("Old name");   // this request still gets what it read

        List<String> fresh = cache.categories(List.of(), () -> { loads.incrementAndGet(); return List.of("New name"); });
        assertThat(fresh).containsExactly("New name");
        assertThat(loads).hasValue(2);
    }

    @Test
    void sharedListsCantBeChangedByOneRequest() {
        List<String> mutable = new ArrayList<>(List.of("Pain & Fever"));
        when(medicines.findActiveCategories()).thenReturn(mutable);
        List<String> got = catalog.categories(null);
        mutable.add("Changed after reading");
        assertThat(catalog.categories(null)).containsExactly("Pain & Fever");
        assertThatThrownBy(() -> got.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theWatcherCoversEverythingTheListsAreMadeFrom() {
        assertThat(CatalogChangeWatcher.WATCHED).containsExactlyInAnyOrder(
                Medicine.class, com.medizon.domain.StoreItem.class, Store.class, StoreArea.class,
                com.medizon.domain.ServiceablePincode.class, com.medizon.domain.Category.class);
    }
}
