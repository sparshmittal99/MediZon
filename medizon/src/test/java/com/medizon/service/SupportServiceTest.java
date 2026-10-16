package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.SupportTicketRepository;
import com.medizon.repository.TicketMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportServiceTest {

    private static long ids = 1;

    private static User user(Role role, Store store) {
        User u = new User();
        u.setId(ids++);
        u.setRole(role);
        u.setFullName(role.getLabel() + " Person");
        u.setEmail(role.name().toLowerCase() + ids + "@example.com");
        u.setStore(store);
        return u;
    }

    private static Store store(long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("Store " + id);
        return s;
    }

    private static SupportTicket ticket(TicketStatus status, Role team, Store store) {
        SupportTicket t = new SupportTicket();
        t.setId(42L);
        t.setUser(user(Role.CUSTOMER, null));
        t.setTopic(TicketTopic.ORDER);
        t.setSubject("Late delivery");
        t.setStatus(status);
        t.setTeam(team);
        t.setStore(store);
        return t;
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void supportAndAdminSeeEverythingTeamsOnlyWhatWasPassedToThem() {
        Store a = store(1), b = store(2);
        SupportTicket withStoreA = ticket(TicketStatus.WITH_TEAM, Role.STORE, a);

        assertThat(SupportService.canView(user(Role.SUPPORT, null), withStoreA)).isTrue();
        assertThat(SupportService.canView(user(Role.ADMIN, null), withStoreA)).isTrue();
        assertThat(SupportService.canView(user(Role.STORE, a), withStoreA)).isTrue();
        assertThat(SupportService.canView(user(Role.STORE, b), withStoreA)).isFalse();        // another store
        assertThat(SupportService.canView(user(Role.PHARMACIST, null), withStoreA)).isFalse();  // another team
        assertThat(SupportService.canView(user(Role.FINANCE, null), ticket(TicketStatus.OPEN, null, a))).isFalse();
        assertThat(SupportService.canView(user(Role.CUSTOMER, null), withStoreA)).isFalse();
    }

    @Test
    void whoeverHoldsTheTicketTalksToTheCustomerSupportCanStillAddNotes() {
        User support = user(Role.SUPPORT, null), pharmacist = user(Role.PHARMACIST, null), admin = user(Role.ADMIN, null);
        SupportTicket open = ticket(TicketStatus.OPEN, null, null);
        SupportTicket withPharmacy = ticket(TicketStatus.WITH_TEAM, Role.PHARMACIST, null);

        assertThat(SupportService.canReply(support, open)).isTrue();
        assertThat(SupportService.canReply(support, withPharmacy)).isFalse();
        assertThat(SupportService.canNote(support, withPharmacy)).isTrue();     // keeps watching
        assertThat(SupportService.canReply(pharmacist, withPharmacy)).isTrue();
        assertThat(SupportService.canReply(admin, withPharmacy)).isTrue();

        assertThat(SupportService.canRedirect(support, open)).isTrue();
        assertThat(SupportService.canRedirect(support, withPharmacy)).isFalse();
        assertThat(SupportService.canRedirect(pharmacist, open)).isFalse();

        assertThat(SupportService.canHandBack(pharmacist, withPharmacy)).isTrue();
        assertThat(SupportService.canHandBack(support, withPharmacy)).isFalse();

        assertThat(SupportService.canClose(support, withPharmacy)).isFalse();   // the team is still on it
        assertThat(SupportService.canClose(admin, withPharmacy)).isTrue();
        assertThat(SupportService.canClose(pharmacist, ticket(TicketStatus.RESOLVED, Role.PHARMACIST, null))).isFalse();
        assertThat(SupportService.canClose(admin, ticket(TicketStatus.CLOSED, null, null))).isFalse();
    }

    @Test
    void theStoreTeamIsOnlyOfferedForOrdersAStoreHandled() {
        assertThat(SupportService.teamsFor(ticket(TicketStatus.OPEN, null, null))).doesNotContain(Role.STORE);
        assertThat(SupportService.teamsFor(ticket(TicketStatus.OPEN, null, store(1)))).contains(Role.STORE);
    }

    // ------------------------------------------------------------------ the whole journey

    private SupportTicketRepository tickets;
    private NotificationService notifications;
    private StaffAlerts staffAlerts;
    private SupportService service;
    private SupportTicket saved;

    @BeforeEach
    void setUp() {
        tickets = mock(SupportTicketRepository.class);
        notifications = mock(NotificationService.class);
        staffAlerts = mock(StaffAlerts.class);
        service = new SupportService(tickets, mock(TicketMessageRepository.class), mock(OrderService.class), notifications,
                staffAlerts, mock(FileStorageService.class));
        when(tickets.save(any())).thenAnswer(i -> {
            saved = i.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        when(tickets.findByIdAndUser(eq(7L), any())).thenAnswer(i -> Optional.ofNullable(saved));
        when(tickets.findById(7L)).thenAnswer(i -> Optional.ofNullable(saved));
    }

    @Test
    void raisePassToTeamSolveCloseRateAndReopen() {
        User customer = user(Role.CUSTOMER, null), support = user(Role.SUPPORT, null), finance = user(Role.FINANCE, null);

        service.create(customer, TicketTopic.PAYMENT, "Charged twice", "I was charged twice for order MZ1.", null, List.of());
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(saved.getNumber()).isEqualTo("TKT-000007");
        verify(staffAlerts).notify(eq(Role.SUPPORT), eq(NotificationType.SUPPORT_TICKET), contains("TKT-000007"), anyString(), eq("/admin/support/7"));

        service.redirect(7L, support, Role.FINANCE, "Please check the double charge.");
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.WITH_TEAM);
        verify(notifications).notify(eq(customer), eq(NotificationType.SUPPORT_UPDATE), contains("payments team"),
                contains("We've passed your issue to our payments team"), eq("/support/tickets/7"));
        verify(staffAlerts).notify(eq(Role.FINANCE), eq(NotificationType.SUPPORT_TICKET), anyString(), anyString(), anyString());
        // The customer sees the hand-off message, never the note for the team
        assertThat(saved.getCustomerMessages()).extracting(TicketMessage::getBody)
                .anyMatch(b -> b.startsWith("We've passed your issue"))
                .noneMatch(b -> b.contains("Please check the double charge"));

        service.staffReply(7L, finance, "Found it, refunding the second charge.", List.of(), false);
        assertThatThrownBy(() -> service.staffReply(7L, support, "Hi!", List.of(), false))
                .isInstanceOf(BusinessException.class);                                  // the team is talking now

        service.handBack(7L, finance, "Refunded ₹74.");
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.RESOLVED);

        service.close(7L, support, SupportService.CLOSING_MESSAGE);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(saved.canRate()).isTrue();

        service.rate(7L, customer, 5, "Quick and polite");
        assertThat(saved.getRating()).isEqualTo(5);
        assertThatThrownBy(() -> service.rate(7L, customer, 4, null)).isInstanceOf(BusinessException.class);

        service.reopen(7L, customer, "The refund still hasn't arrived.");
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(saved.getReopenCount()).isEqualTo(1);
        assertThat(saved.getRating()).isNull();
    }

    @Test
    void aClosedTicketCanOnlyBeReopenedForAWeek() {
        User customer = user(Role.CUSTOMER, null);
        service.create(customer, TicketTopic.OTHER, "Question", "Hello", null, List.of());
        saved.setStatus(TicketStatus.CLOSED);
        saved.setClosedAt(LocalDateTime.now().minusDays(SupportTicket.REOPEN_DAYS + 1));
        assertThatThrownBy(() -> service.reopen(7L, customer, "Still broken")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.customerReply(7L, customer, "Hello?", List.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void staffCantRaiseTicketsAndCustomersCantFlood() {
        assertThatThrownBy(() -> service.create(user(Role.SUPPORT, null), TicketTopic.OTHER, "x", "y", null, List.of()))
                .isInstanceOf(BusinessException.class);
        User customer = user(Role.CUSTOMER, null);
        when(tickets.countByUserAndStatusNot(customer, TicketStatus.CLOSED)).thenReturn((long) SupportService.MAX_OPEN_PER_CUSTOMER);
        assertThatThrownBy(() -> service.create(customer, TicketTopic.OTHER, "x", "y", null, List.of()))
                .hasMessageContaining("open tickets");
    }
}
