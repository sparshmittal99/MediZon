package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.Offer;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.CartRepository;
import com.medizon.repository.MedicineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One medicine for a family member and for someone outside the family: one cart line, each keeps their packs. */
class CartOthersTest {

    private final User user = new User();
    private CartItemRepository lines;
    private CartService service;
    private Medicine acitrom;
    private Member me, others, common;
    private MedicineRepository medicines;
    private CatalogService catalog;
    private CartItem saved;

    private static Member member(long id, MemberKind kind) {
        Member m = new Member();
        m.setId(id);
        m.setKind(kind);
        m.setName(kind.name());
        return m;
    }

    @BeforeEach
    void setUp() {
        lines = mock(CartItemRepository.class);
        medicines = mock(MedicineRepository.class);
        MemberService members = mock(MemberService.class);
        catalog = mock(CatalogService.class);
        service = new CartService(lines, mock(CartRepository.class), medicines, members, catalog, mock(CouponService.class));

        acitrom = new Medicine();
        acitrom.setId(3L);
        acitrom.setName("Acitrom 3");
        acitrom.setActive(true);
        acitrom.setPrescriptionRequired(true);
        Store store = new Store();
        StoreItem listing = new StoreItem();
        listing.setStore(store);
        listing.setMedicine(acitrom);
        listing.setStock(100);
        when(catalog.offer(eq(acitrom), any(), anyInt())).thenReturn(new Offer(acitrom, listing, null, 100));
        when(medicines.findById(3L)).thenReturn(Optional.of(acitrom));

        me = member(1, MemberKind.SELF);
        others = member(3, MemberKind.OTHERS);
        common = member(2, MemberKind.COMMON);
        when(members.self(user)).thenReturn(me);
        when(members.common(user)).thenReturn(common);
        // The line, once saved, is what the next add finds
        when(lines.findByUserAndMedicine(user, acitrom)).thenAnswer(i -> Optional.ofNullable(saved));
        when(lines.save(any())).thenAnswer(i -> saved = i.getArgument(0));
    }

    @Test
    void othersPacksAreKeptNextToAFamilyMembersPacks() {
        CartItem first = new CartItem();          // Sparsh's prescription approved first: 2 packs for him
        first.setUser(user);
        first.setMedicine(acitrom);
        first.allocate(Map.of(me, 2));
        saved = first;

        service.add(user, 3L, 2, List.of(others), null);     // then Dummy Patient A's, outside the family: 2 more

        assertEquals(4, saved.getQuantity());
        assertEquals(2, saved.packsFor(me.getId()));
        assertEquals(2, saved.packsFor(others.getId()));
    }

    @Test
    void newLinesGoToTheCommonShelfForOtcAndTheAccountHolderForPrescriptionMedicines() {
        Medicine crocin = new Medicine();
        crocin.setId(5L);
        crocin.setName("Crocin");
        crocin.setActive(true);
        StoreItem listing = new StoreItem();
        listing.setStore(new Store());
        listing.setMedicine(crocin);
        listing.setStock(100);
        when(catalog.offer(eq(crocin), any(), anyInt())).thenReturn(new Offer(crocin, listing, null, 100));
        when(medicines.findById(5L)).thenReturn(Optional.of(crocin));
        when(lines.findByUserAndMedicine(user, crocin)).thenReturn(Optional.empty());

        service.add(user, 5L, 2, null);
        assertEquals(2, saved.packsFor(common.getId()));

        saved = null;
        service.add(user, 3L, 1, null);       // Acitrom needs a prescription
        assertEquals(1, saved.packsFor(me.getId()));
    }
}
