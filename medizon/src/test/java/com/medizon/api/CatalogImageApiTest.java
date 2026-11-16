package com.medizon.api;

import com.medizon.domain.Medicine;
import com.medizon.domain.User;
import com.medizon.service.BusinessException;
import com.medizon.service.CatalogImageService;
import com.medizon.service.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON catalog-images.js reads: matches with their products, an uploaded picture, a refusal it can show. */
class CatalogImageApiTest {

    private CatalogImageService images;
    private MockMvc mvc;
    private final User admin = new User();

    @BeforeEach
    void setUp() {
        images = mock(CatalogImageService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.get()).thenReturn(admin);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogImageApi(images, currentUser)).setControllerAdvice(new ApiErrors()).build();
    }

    @Test
    void matchesComeBackWithTheirProducts() throws Exception {
        when(images.match(eq(admin), anyList())).thenReturn(List.of(new CatalogImageService.Match("dolo 650.jpg",
                CatalogImageService.Status.MATCHED,
                List.of(new CatalogImageService.Candidate(1L, "MZP00001", "Dolo 650 Tablet", "Micro Labs", null)))));
        mvc.perform(post("/api/catalog-images/match").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"dolo 650.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].file").value("dolo 650.jpg"))
                .andExpect(jsonPath("$[0].status").value("MATCHED"))
                .andExpect(jsonPath("$[0].candidates[0].id").value(1))
                .andExpect(jsonPath("$[0].candidates[0].name").value("Dolo 650 Tablet"))
                .andExpect(jsonPath("$[0].candidates[0].code").value("MZP00001"));
    }

    @Test
    void anUploadedPictureComesBackWithItsAddress() throws Exception {
        Medicine m = new Medicine();
        m.setId(1L);
        m.setName("Dolo 650 Tablet");
        m.setImageUrl("/medicine-images/x.png");
        when(images.setImage(eq(admin), eq(1L), any(), eq(true))).thenReturn(m);
        mvc.perform(multipart("/api/catalog-images/1").file(new MockMultipartFile("file", "dolo.png", "image/png", new byte[]{1}))
                        .param("replace", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/medicine-images/x.png"));
    }

    @Test
    void aRefusalIsJsonTheScriptCanShow() throws Exception {
        when(images.setImage(eq(admin), eq(1L), any(), eq(false)))
                .thenThrow(new BusinessException("Dolo 650 Tablet already has a picture."));
        mvc.perform(multipart("/api/catalog-images/1").file(new MockMultipartFile("file", "dolo.png", "image/png", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Dolo 650 Tablet already has a picture."));
    }
}
