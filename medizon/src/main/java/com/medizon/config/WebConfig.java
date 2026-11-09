package com.medizon.config;

import com.medizon.service.FileStorageService;
import com.medizon.web.PhoneRequiredInterceptor;
import com.medizon.web.VisitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final VisitInterceptor visitInterceptor;
    private final PhoneRequiredInterceptor phoneRequired;
    private final FileStorageService fileStorage;

    /** Uploaded medicine images are public, unlike prescription files. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(FileStorageService.MEDICINE_IMAGE_PATH + "**")
                .addResourceLocations(fileStorage.imageRoot().toUri().toString());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/**", "/api/**", "/error", "/css/**", "/js/**", "/images/**", "/uploads/**",
                        FileStorageService.MEDICINE_IMAGE_PATH + "**");
        // Customers without a mobile number add one first (pages only; the JSON API and sign-in pages are left alone)
        registry.addInterceptor(phoneRequired)
                .addPathPatterns("/**")
                .excludePathPatterns("/account/phone", "/account/secure", "/logout", "/login", "/login/**", "/oauth2/**",
                        "/api/**", "/error", "/help", "/css/**", "/js/**", "/images/**", "/fonts/**", "/uploads/**",
                        "/favicon*", "/apple-touch-icon*", "/.well-known/**", "/admin/**",
                        FileStorageService.MEDICINE_IMAGE_PATH + "**");
    }
}
