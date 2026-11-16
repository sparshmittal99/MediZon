package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.MedicineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.medizon.service.CatalogImageService.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CatalogImageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};

    private MedicineRepository medicines;
    private FileStorageService files;
    private CatalogImageService service;
    private final List<Medicine> all = new ArrayList<>();
    private User admin;

    @BeforeEach
    void setUp() {
        medicines = mock(MedicineRepository.class);
        files = mock(FileStorageService.class);
        service = new CatalogImageService(medicines, files);
        when(medicines.findAll()).thenReturn(all);
        admin = user(Role.ADMIN);
        add(1, "Dolo 650 Tablet");
        add(2, "Dolo 500 Tablet");
        add(3, "Paracetamol 500mg Tablet");
        add(4, "Crocin Advance");
        add(5, "Vitamin D3 60K Capsule");
        add(6, "Crocin");
    }

    private Medicine add(long id, String name) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setProductCode(MedicineService.productCode(id));
        all.add(m);
        when(medicines.findById(id)).thenReturn(Optional.of(m));
        return m;
    }

    private static User user(Role role) {
        User u = new User();
        u.setRole(role);
        return u;
    }

    private CatalogImageService.Match one(String file) {
        return service.match(admin, List.of(file)).get(0);
    }

    @Test
    void partOfTheNameFindsTheProductWhenItTellsItApart() {
        assertThat(one("dolo 650.jpg")).satisfies(m -> {
            assertThat(m.status()).isEqualTo(MATCHED);
            assertThat(m.candidates().get(0).name()).isEqualTo("Dolo 650 Tablet");
        });
        assertThat(one("para 500.png").candidates().get(0).name()).isEqualTo("Paracetamol 500mg Tablet");
        assertThat(one("Vitamin-D3 60k.jpeg").status()).isEqualTo(MATCHED);
        assertThat(one("DOLO_650_TABLET.webp").status()).isEqualTo(MATCHED);
    }

    @Test
    void theExactNameWinsOverLongerNamesThatStartTheSame() {
        CatalogImageService.Match m = one("crocin.jpg");
        assertThat(m.status()).isEqualTo(MATCHED);
        assertThat(m.candidates().get(0).name()).isEqualTo("Crocin");
    }

    @Test
    void aNameThatFitsSeveralProductsIsLeftToChoose() {
        CatalogImageService.Match m = one("dolo.jpg");
        assertThat(m.status()).isEqualTo(CHOOSE);
        assertThat(m.candidates()).extracting(CatalogImageService.Candidate::name).contains("Dolo 500 Tablet", "Dolo 650 Tablet");
    }

    @Test
    void numbersMustBeWhole() {
        // "50" isn't "500" or "650": nothing fits, but products sharing a word are offered
        CatalogImageService.Match m = one("dolo 50.jpg");
        assertThat(m.status()).isEqualTo(CHOOSE);
        assertThat(m.candidates()).extracting(CatalogImageService.Candidate::name).startsWith("Dolo 500 Tablet");
    }

    @Test
    void extraWordsInTheFileNameAreAGuessToCheck() {
        CatalogImageService.Match m = one("Crocin Advance front (1).jpg");
        assertThat(m.status()).isEqualTo(CHECK);
        assertThat(m.candidates().get(0).name()).isEqualTo("Crocin Advance");
    }

    @Test
    void aProductCodePicksTheProduct() {
        CatalogImageService.Match m = one("MZP00002 - Copy.jpg");
        assertThat(m.status()).isEqualTo(MATCHED);
        assertThat(m.candidates()).extracting(CatalogImageService.Candidate::id).containsExactly(2L);
    }

    @Test
    void nothingInCommonIsNoMatch() {
        assertThat(one("IMG_2041.jpg").status()).isEqualTo(NONE);
        assertThat(one("IMG_2041.jpg").candidates()).isEmpty();
    }

    @Test
    void tooManyPicturesAtOnceAreRefused() {
        List<String> names = new ArrayList<>();
        for (int k = 0; k <= CatalogImageService.MAX_FILES; k++) names.add("p" + k + ".jpg");
        assertThatThrownBy(() -> service.match(admin, names)).isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- uploading

    private static MockMultipartFile png() { return new MockMultipartFile("file", "dolo.png", "image/png", PNG); }

    @Test
    void theAdminPicturesAProductAndReplacesOnlyWhenAsked() {
        Medicine dolo = all.get(0);
        when(files.storeMedicineImage(any())).thenReturn("/medicine-images/new.png");
        service.setImage(admin, 1L, png(), false);
        assertThat(dolo.getImageUrl()).isEqualTo("/medicine-images/new.png");

        assertThatThrownBy(() -> service.setImage(admin, 1L, png(), false)).hasMessageContaining("already has a picture");
        when(files.storeMedicineImage(any())).thenReturn("/medicine-images/newer.png");
        service.setImage(admin, 1L, png(), true);
        assertThat(dolo.getImageUrl()).isEqualTo("/medicine-images/newer.png");
        verify(files).deleteMedicineImage("/medicine-images/new.png");
    }

    @Test
    void onlyTheAdminPicturesProducts() {
        for (Role role : List.of(Role.STORE, Role.PHARMACIST, Role.SUPPORT, Role.FINANCE, Role.CUSTOMER)) {
            User staff = user(role);
            assertThatThrownBy(() -> service.setImage(staff, 1L, png(), false)).hasMessageContaining("Only the admin");
            assertThatThrownBy(() -> service.match(staff, List.of("dolo.jpg"))).hasMessageContaining("Only the admin");
        }
        verify(files, never()).storeMedicineImage(any());
        assertThat(all.get(0).getImageUrl()).isNull();
    }
}
