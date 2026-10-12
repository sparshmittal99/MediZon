package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.MemberRepository;
import com.medizon.repository.NotificationDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The people an account manages medicines for. Every customer has an entry for themselves and a "common medicines"
 * shelf for household OTC medicines; both are created on first use and can't be removed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    public static final int MAX_FAMILY = 12;

    private final MemberRepository members;
    private final MedicationPlanRepository plans;
    private final CartItemRepository cartItems;
    private final NotificationDeviceRepository devices;

    /** The account holder and their family, account holder first. */
    @Transactional
    public List<Member> people(User user) {
        self(user);
        return members.findByUserAndRemovedFalseOrderByIdAsc(user).stream()
                .filter(m -> !m.isCommon())
                .sorted(Comparator.comparing(Member::getKind).thenComparing(Member::getId))
                .toList();
    }

    /** People plus the common shelf, for tabs, pickers and notification settings. */
    @Transactional
    public List<Member> all(User user) {
        List<Member> out = new ArrayList<>(people(user));
        out.add(common(user));
        return out;
    }

    @Transactional
    public Member self(User user) {
        return members.findFirstByUserAndKind(user, MemberKind.SELF).orElseGet(() -> create(user, MemberKind.SELF, user.getFullName()));
    }

    @Transactional
    public Member common(User user) {
        return members.findFirstByUserAndKind(user, MemberKind.COMMON).orElseGet(() -> create(user, MemberKind.COMMON, "Common medicines"));
    }

    private Member create(User user, MemberKind kind, String name) {
        Member m = new Member();
        m.setUser(user);
        m.setKind(kind);
        m.setName(name == null || name.isBlank() ? "Me" : name);
        return members.save(m);
    }

    public Member get(Long id, User user) {
        return members.findByIdAndUserAndRemovedFalse(id, user)
                .orElseThrow(() -> new BusinessException("That family member doesn't exist."));
    }

    @Transactional
    public Member save(User user, Long id, String name, String relation) {
        String n = name == null ? "" : name.trim();
        String r = relation == null ? "" : relation.trim();
        if (n.isEmpty()) throw new BusinessException("Enter a name.");
        if (n.length() > 60) throw new BusinessException("Keep the name under 60 characters.");
        if (r.length() > 40) throw new BusinessException("Keep the relation under 40 characters.");

        Member m;
        if (id == null) {
            self(user);   // the account holder always comes first
            if (members.countByUserAndKindAndRemovedFalse(user, MemberKind.FAMILY) >= MAX_FAMILY) {
                throw new BusinessException("You can add up to " + MAX_FAMILY + " family members.");
            }
            m = new Member();
            m.setUser(user);
            m.setKind(MemberKind.FAMILY);
        } else {
            m = get(id, user);
            if (m.isCommon()) throw new BusinessException("The common medicines shelf can't be renamed.");
        }
        m.setName(n);
        m.setRelation(m.isSelf() || r.isEmpty() ? null : r);
        return members.save(m);
    }

    /**
     * Removes a family member: their reminders stop and they disappear from the cart and device settings.
     * Past orders keep their name.
     */
    @Transactional
    public Member remove(Long id, User user) {
        Member m = get(id, user);
        if (!m.getKind().equals(MemberKind.FAMILY)) throw new BusinessException("This entry can't be removed.");
        m.setRemoved(true);
        plans.findByMember(m).forEach(p -> p.setActive(false));

        Member self = self(user);
        for (CartItem c : cartItems.findByUserOrderByIdAsc(user)) {
            if (c.getMembers().removeIf(x -> x.getId().equals(m.getId())) && c.getMembers().isEmpty()) {
                c.getMembers().add(self);
            }
        }
        devices.findByUserOrderByLastSeenAtDesc(user).forEach(d -> d.getMembers().removeIf(x -> x.getId().equals(m.getId())));
        return m;
    }

    /** Plans and cart lines from before family members existed belong to the account holder. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void assignLegacyRowsToAccountHolder() {
        List<MedicationPlan> legacyPlans = plans.findByMemberIsNull();
        legacyPlans.forEach(p -> p.setMember(self(p.getUser())));
        // The common shelf has no schedules: anything waiting for one there is simply stock at home
        plans.findBySetupRequiredTrue().stream().filter(MedicationPlan::isOnCommonShelf).forEach(p -> {
            p.setSetupRequired(false);
            p.setActive(true);
            p.getReminderTimes().clear();
            p.setReorderMode(ReorderMode.OFF);
        });
        List<CartItem> legacyCart = cartItems.findWithoutMembers();
        legacyCart.forEach(c -> c.getMembers().add(self(c.getUser())));
        if (!legacyPlans.isEmpty() || !legacyCart.isEmpty()) {
            log.info("Assigned {} plan(s) and {} cart line(s) to their account holders", legacyPlans.size(), legacyCart.size());
        }
    }
}
