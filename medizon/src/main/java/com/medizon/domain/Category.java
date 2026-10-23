package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A product category in the master catalogue ("Pain & Fever"). The admin adds them, and a product saved or imported
 * with a new category adds it too. Products keep the name itself ({@link Medicine#getCategory()}), so a category can
 * exist before any product is in it. Names are unique whatever their case.
 */
@Entity
@Table(name = "categories")
@Getter @Setter @NoArgsConstructor
public class Category {

    /** Longest name the admin can type for a new category. */
    public static final int MAX_NAME = 60;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public Category(String name) { this.name = name; }
}
