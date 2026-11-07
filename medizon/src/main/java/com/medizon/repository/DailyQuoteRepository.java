package com.medizon.repository;

import com.medizon.domain.DailyQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface DailyQuoteRepository extends JpaRepository<DailyQuote, LocalDate> {
}
