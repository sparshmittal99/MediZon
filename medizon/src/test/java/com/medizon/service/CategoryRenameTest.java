package com.medizon.service;

import com.medizon.domain.Category;
import com.medizon.repository.CategoryRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Renaming a category moves its products with it; renaming it to another category's name merges the two. */
class CategoryRenameTest {

    private MedicineRepository medicines;
    private CategoryRepository categories;
    private MedicineService service;
    private Category painLower, vitamins;

    private static Category category(long id, String name) {
        Category c = new Category(name);
        c.setId(id);
        return c;
    }

    @BeforeEach
    void setUp() {
        medicines = mock(MedicineRepository.class);
        categories = mock(CategoryRepository.class);
        service = new MedicineService(medicines, mock(FileStorageService.class), categories, mock(StoreItemRepository.class));
        painLower = category(1, "Pain & fever");
        vitamins = category(2, "Vitamins & Supplements");
        when(categories.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(categories.findFirstByNameIgnoreCase("Pain & fever")).thenReturn(Optional.of(painLower));
        when(categories.findFirstByNameIgnoreCase("Pain & Fever")).thenReturn(Optional.of(painLower));
        when(categories.findFirstByNameIgnoreCase("Vitamins & supplements")).thenReturn(Optional.of(vitamins));
        when(categories.findFirstByNameIgnoreCase("Vitamins & Supplements")).thenReturn(Optional.of(vitamins));
        when(categories.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void changingOnlyTheCapitalsRenamesTheCategoryAndItsProducts() {
        when(medicines.moveCategory("Pain & fever", "Pain & Fever")).thenReturn(5);
        assertThat(service.renameCategory("Pain & fever", "Pain & Fever")).isEqualTo("Pain & fever is now Pain & Fever (5 products).");
        assertThat(painLower.getName()).isEqualTo("Pain & Fever");
        verify(medicines).moveCategory("Pain & fever", "Pain & Fever");
        verify(categories, never()).delete(any());
    }

    @Test
    void renamingToAnotherCategoryMergesThem() {
        Category heart = category(3, "Heart & blood pressure");
        Category cardiac = category(4, "Cardiac & Blood Pressure");
        when(categories.findFirstByNameIgnoreCase("Heart & blood pressure")).thenReturn(Optional.of(heart));
        when(categories.findFirstByNameIgnoreCase("cardiac & blood pressure")).thenReturn(Optional.of(cardiac));
        when(medicines.moveCategory("Heart & blood pressure", "Cardiac & Blood Pressure")).thenReturn(2);
        assertThat(service.renameCategory("Heart & blood pressure", "cardiac & blood pressure"))
                .isEqualTo("Heart & blood pressure merged into Cardiac & Blood Pressure: 2 products moved.");
        verify(categories).delete(heart);
    }

    @Test
    void aRenameNeedsANewName() {
        assertThatThrownBy(() -> service.renameCategory("Vitamins & Supplements", " Vitamins &  Supplements "))
                .hasMessageContaining("already has that name");
        assertThatThrownBy(() -> service.renameCategory("Vitamins & Supplements", "")).hasMessageContaining("Enter the new name");
        assertThatThrownBy(() -> service.renameCategory("Skin care", "Skin")).hasMessageContaining("isn't a category");
    }
}
