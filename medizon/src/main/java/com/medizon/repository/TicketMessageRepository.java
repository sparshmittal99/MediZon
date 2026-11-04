package com.medizon.repository;

import com.medizon.domain.TicketMessage;
import com.medizon.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    /** Photos still needed: on tickets not closed, or closed after {@code since}. */
    @Query("""
            select a.storedFileName from TicketMessage m join m.attachments a join m.ticket t
            where t.status <> :closed or t.closedAt is null or t.closedAt > :since""")
    List<String> photoNamesInUse(@Param("closed") TicketStatus closed, @Param("since") LocalDateTime since);
}
