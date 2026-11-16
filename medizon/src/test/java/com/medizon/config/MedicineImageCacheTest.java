package com.medizon.config;

import com.medizon.service.FileStorageService;
import com.medizon.web.PhoneRequiredInterceptor;
import com.medizon.web.VisitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Product pictures are kept by browsers, the app and Cloudflare; pages still aren't (they show personal data). */
@SpringJUnitWebConfig(MedicineImageCacheTest.Config.class)
class MedicineImageCacheTest {

    static final Path DIR;
    static {
        try {
            DIR = Files.createTempDirectory("mz-images");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebConfig.class)
    static class Config {
        @Bean FileStorageService fileStorage() throws IOException { return new FileStorageService(DIR.toString()); }
        @Bean VisitInterceptor visitInterceptor() { return mock(VisitInterceptor.class); }
        @Bean PhoneRequiredInterceptor phoneRequired() { return mock(PhoneRequiredInterceptor.class); }
        @Bean Page page() { return new Page(); }

        /** Spring Security's default headers (no-store on everything that sets nothing itself), as in production. */
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    @RestController
    static class Page {
        @GetMapping("/some-page")
        String page() { return "hello"; }
    }

    @Autowired
    WebApplicationContext context;

    @Test
    void aProductPictureIsKeptForAYear() throws Exception {
        Files.write(DIR.resolve("medicine-images").resolve("0b7c2a1e-pic.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/medicine-images/0b7c2a1e-pic.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().doesNotExist("Pragma"));
    }

    @Test
    void pagesAreStillNotStored() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/some-page"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
