package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "medicine_id"}))
@Getter @Setter @NoArgsConstructor
public class CartItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @ManyToOne(optional = false)
    private Medicine medicine;

    private int quantity;

    /** Who it's for: one or more family members, or the common shelf. The customer picks in the cart. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "cart_item_members",
            joinColumns = @JoinColumn(name = "cart_item_id"), inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<Member> members = new HashSet<>();

    public BigDecimal getLineTotal() {
        return medicine.getPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public boolean hasMember(Long memberId) {
        return members.stream().anyMatch(m -> m.getId().equals(memberId));
    }

    /** Members in a stable order (account holder first, common last) for display. */
    public List<Member> getSortedMembers() {
        return members.stream().sorted(Comparator.comparing(Member::getKind).thenComparing(Member::getId)).toList();
    }
}
