package com.medizon.config;

import com.medizon.service.FileStorageService;
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
    }
}
