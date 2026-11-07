package com.medizon.repository;

import com.medizon.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findTop30ByOrderBySentAtDesc();
}
