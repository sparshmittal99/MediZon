package com.medizon.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * Stores prescription files (private, streamed through a controller) and medicine images
 * (public, served at {@value #MEDICINE_IMAGE_PATH}) on local disk. Swap for S3 / GCS in production.
 */
@Service
public class FileStorageService {

    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "application/pdf", ".pdf");

    private static final Map<String, String> IMAGES = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp", "image/gif", ".gif");

    /** Public URL prefix for uploaded medicine images. */
    public static final String MEDICINE_IMAGE_PATH = "/medicine-images/";

    private final Path root;
    private final Path imageRoot;

    public FileStorageService(@Value("${medizon.upload-dir:uploads}") String dir) throws IOException {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.imageRoot = root.resolve("medicine-images");
        Files.createDirectories(imageRoot);
    }

    /** Folder that holds medicine images, for the static resource handler. */
    public Path imageRoot() { return imageRoot; }

    /** Saves a product photo and returns its public URL path. */
    public String storeMedicineImage(MultipartFile file) {
        String ext = IMAGES.get(file.getContentType());
        if (ext == null) throw new BusinessException("Upload the image as JPG, PNG, WebP or GIF.");
        String name = UUID.randomUUID() + ext;
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, imageRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("The image couldn't be saved. Try again.");
        }
        return MEDICINE_IMAGE_PATH + name;
    }

    /** Removes a previously uploaded medicine image; external URLs are left alone. */
    public void deleteMedicineImage(String url) {
        if (url == null || !url.startsWith(MEDICINE_IMAGE_PATH)) return;
        Path p = imageRoot.resolve(url.substring(MEDICINE_IMAGE_PATH.length())).normalize();
        if (!p.startsWith(imageRoot)) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    /** Saves a return photo privately (streamed through a controller like prescriptions) and returns its stored name. */
    public String storeReturnImage(MultipartFile file) { return storePrivateImage(file, "returns"); }

    /** Saves a photo on a support ticket privately and returns its stored name. */
    public String storeSupportImage(MultipartFile file) { return storePrivateImage(file, "support"); }

    private String storePrivateImage(MultipartFile file, String folder) {
        String ext = IMAGES.get(file.getContentType());
        if (ext == null) throw new BusinessException("Upload photos as JPG, PNG, WebP or GIF.");
        String name = folder + "/" + UUID.randomUUID() + ext;
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(root.resolve(folder));
            Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("The photo couldn't be saved. Try again.");
        }
        return name;
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("Choose a file to upload.");
        String ext = ALLOWED.get(file.getContentType());
        if (ext == null) throw new BusinessException("Upload a JPG, PNG or PDF file.");
        String name = UUID.randomUUID() + ext;
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("The file couldn't be saved. Try again.");
        }
        return name;
    }

    /** Deletes a private upload (prescription or return photo); a missing file is fine. */
    public void delete(String name) {
        if (name == null || name.isBlank()) return;
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root)) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    public Resource load(String name) {
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw new NotFoundException("File not found.");
        return new FileSystemResource(p);
    }
}
