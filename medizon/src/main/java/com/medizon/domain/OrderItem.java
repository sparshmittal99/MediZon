package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Getter @Setter @NoArgsConstructor
public class OrderItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Order order;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** Snapshot of name and price at order time. */
    private String medicineName;

    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    private int quantity;

    @Column(precision = 10, scale = 2)
    private BigDecimal lineTotal;

    /** Snapshot of the tax details at order time, for the invoice. Null on orders placed before invoices existed. */
    @Column(length = 8)
    private String hsnCode;

    @Column(precision = 5, scale = 2)
    private BigDecimal gstRate;

    /**
     * Whether the medicine was returnable when ordered, so later catalog changes don't alter what was promised.
     * Null on orders from before returnability existed: those follow the medicine's current setting.
     */
    private Boolean returnableAtOrder;

    /** Expiry of the batch sold, snapshotted at order time; carried to the supply at home on delivery. */
    private LocalDate expiryDate;

    /** The patient(s) it was for when ordered, printed on the invoice; kept as text so later renames don't change it. */
    @Column(length = 200)
    private String patientName;

    /** Who prescribed it and when, from the prescription(s) it used, for the invoice: "Dr. Ambrose, 4 Oct 2026". */
    @Column(length = 300)
    private String prescribedBy;

    /** The prescription medicines this line used; empty for OTC medicines and orders from before this was recorded. */
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL)
    private List<PrescriptionUse> prescriptionUses = new ArrayList<>();

    /** Who it's for. Customer-side only: admins see the medicines, not the family. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "order_item_members",
            joinColumns = @JoinColumn(name = "order_item_id"), inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<Member> members = new HashSet<>();

    /** Packs per member id as ordered; empty on orders from before per-person packs (shared evenly). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_item_packs", joinColumns = @JoinColumn(name = "order_item_id"))
    @MapKeyColumn(name = "member_id")
    @Column(name = "packs")
    private Map<Long, Integer> packs = new HashMap<>();

    /** {@code total} (units or packs) shared between {@code targets} in proportion to the packs each was ordered. */
    public int[] shareOf(List<Member> targets, int total) {
        return Shares.split(total, targets.stream().mapToInt(m -> packs.getOrDefault(m.getId(), 0)).toArray());
    }

    public boolean isReturnable() {
        return returnableAtOrder != null ? returnableAtOrder : medicine.isReturnable();
    }

    /** Members in a stable order (account holder first, common last). */
    public List<Member> getSortedMembers() {
        return members.stream().sorted(Comparator.comparing(Member::getKind).thenComparing(Member::getId)).toList();
    }

    /** "Me (2), Asha (1)", or just the names when packs per person weren't recorded or one person had them all. */
    public String getMembersLabel() {
        List<Member> who = getSortedMembers();
        if (who.size() < 2 || packs.isEmpty()) return who.stream().map(Member::getLabel).collect(Collectors.joining(", "));
        return who.stream().map(m -> m.getLabel() + " (" + packs.getOrDefault(m.getId(), 0) + ")").collect(Collectors.joining(", "));
    }
}
