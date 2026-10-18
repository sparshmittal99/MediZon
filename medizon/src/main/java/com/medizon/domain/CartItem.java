package com.medizon.domain;

import com.medizon.dto.Offer;
import com.medizon.dto.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "medicine_id"}))
@Getter @Setter @NoArgsConstructor
public class CartItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    /** The account's active cart. Lines leave with checkout, so (user, medicine) stays unique. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Cart cart;

    @ManyToOne(optional = false)
    private Medicine medicine;

    private int quantity;

    /** Who it's for: one or more family members, or the common shelf. The customer picks in the cart. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "cart_item_members",
            joinColumns = @JoinColumn(name = "cart_item_id"), inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<Member> members = new HashSet<>();

    /**
     * Packs per member id: how the line's packs are shared out. Always adds up to {@link #quantity}; lines from before
     * per-person packs have none and are split evenly (see {@link #getAllocation()}).
     */
    /** Who the "Others" share is for, when it came from a prescription for someone outside the family. */
    @Column(length = 100)
    private String othersPatientName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cart_item_packs", joinColumns = @JoinColumn(name = "cart_item_id"))
    @MapKeyColumn(name = "member_id")
    @Column(name = "packs")
    private Map<Long, Integer> packs = new HashMap<>();

    /** Which store would deliver this line to the shopper, and at what price; worked out for each page, not stored. */
    @Transient
    private Offer offer;

    /** The product as the shopper sees it, priced by the store that would deliver it. */
    public Product getProduct() { return new Product(medicine, offer != null ? offer : Offer.none(medicine)); }

    /** Enough packs at a store that delivers here (or, without a location, at some store). */
    public boolean isAvailable() { return offer != null && offer.canSell(quantity); }

    public BigDecimal getUnitPrice() { return offer != null ? offer.price() : medicine.getPrice(); }

    /** Packs for each member it's for, account holder first; members left with no pack are skipped. */
    public Map<Member, Integer> getAllocation() {
        List<Member> who = getSortedMembers();
        Map<Member, Integer> out = new LinkedHashMap<>();
        boolean recorded = !packs.isEmpty() && who.stream().allMatch(m -> packs.containsKey(m.getId()));
        int[] split = Shares.split(quantity, recorded ? who.stream().mapToInt(m -> packs.get(m.getId())).toArray() : new int[who.size()]);
        for (int i = 0; i < who.size(); i++) if (split[i] > 0) out.put(who.get(i), split[i]);
        return out;
    }

    /** Packs per member id, for the page and the API. */
    public Map<Long, Integer> getAllocationById() {
        Map<Long, Integer> out = new LinkedHashMap<>();
        getAllocation().forEach((m, n) -> out.put(m.getId(), n));
        return out;
    }

    public int packsFor(Long memberId) {
        return getAllocationById().getOrDefault(memberId, 0);
    }

    /** Replaces who it's for and how many packs each gets; the quantity becomes the total. */
    public void allocate(Map<Member, Integer> perMember) {
        members.clear();
        packs.clear();
        perMember.forEach((m, n) -> {
            if (n != null && n > 0) {
                members.add(m);
                packs.put(m.getId(), n);
            }
        });
        quantity = packs.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** "Me (2), Asha (1)", or just "Me" when one person has every pack. */
    /** The patients the packs are for, for checkout: "Asha Sharma, Ravi Kumar"; empty when none has a name. */
    public String getPatientLabel() {
        return getAllocation().keySet().stream()
                .map(m -> m.isOthers() ? othersPatientName : m.getPatient())
                .filter(n -> n != null && !n.isBlank()).distinct().collect(Collectors.joining(", "));
    }

    public String getAllocationLabel() {
        Map<Member, Integer> a = getAllocation();
        if (a.size() == 1) return a.keySet().iterator().next().getLabel();
        return a.entrySet().stream().map(e -> e.getKey().getLabel() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
    }

    public BigDecimal getLineTotal() {
        return getUnitPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public boolean hasMember(Long memberId) {
        return members.stream().anyMatch(m -> m.getId().equals(memberId));
    }

    /** Members in a stable order (account holder first, common last) for display. */
    public List<Member> getSortedMembers() {
        return members.stream().sorted(Comparator.comparing(Member::getKind).thenComparing(Member::getId)).toList();
    }
}
