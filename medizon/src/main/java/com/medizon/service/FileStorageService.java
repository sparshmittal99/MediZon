package com.medizon.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores prescription files (private, streamed through a controller) and medicine images
 * (public, served at {@value #MEDICINE_IMAGE_PATH}) on local disk. Swap for S3 / GCS in production.
 *
 * <p>Uploads are never trusted: the uploader's file name is thrown away (each file gets a random name), the type is
 * read from the file's own bytes ({@link UploadInspector}), not its name or the Content-Type the browser sent, and
 * only images and plain PDFs are kept, with the extension of what they really are. So {@code script.js}, an HTML page or
 * an SVG renamed to "photo.png" is refused, and no upload can choose where it's written. Nothing uploaded is ever run;
 * files are served back only with their checked image or PDF type, {@code X-Content-Type-Options: nosniff} and the
 * site's Content-Security-Policy, so a browser won't treat one as a page or script either.
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
        Checked c = check(file, IMAGES, "Upload the image as JPG, PNG, WebP or GIF.");
        String name = UUID.randomUUID() + c.ext();
        write(imageRoot.resolve(name), c.bytes(), "The image couldn't be saved. Try again.");
        return MEDICINE_IMAGE_PATH + name;
    }

    /**
     * What {@code file} really is, from its bytes: the content type to keep with it and serve it as. Call after storing
     * it (which refuses anything that isn't allowed).
     */
    public String typeOf(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String type = UploadInspector.detect(in.readNBytes(16));
            if (type == null) throw new BusinessException("That file type isn't allowed.");
            return type;
        } catch (IOException e) {
            throw new BusinessException("The file couldn't be read. Try again.");
        }
    }

    /** An upload checked from its own bytes: what it is and the bytes to write. */
    private record Checked(String type, String ext, byte[] bytes) {}

    private static Checked check(MultipartFile file, Map<String, String> allowed, String refusal) {
        if (file == null || file.isEmpty()) throw new BusinessException("Choose a file to upload.");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("The file couldn't be read. Try again.");
        }
        String type = UploadInspector.detect(bytes);
        String ext = type == null ? null : allowed.get(type);
        if (ext == null) throw new BusinessException(refusal);
        if (type.equals("application/pdf")) UploadInspector.requireSafePdf(bytes);
        return new Checked(type, ext, bytes);
    }

    /** Writes a new file; it never replaces one, and the random name keeps it inside its folder. */
    private static void write(Path target, byte[] bytes, String failure) {
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new BusinessException(failure);
        }
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
        Checked c = check(file, IMAGES, "Upload photos as JPG, PNG, WebP or GIF.");
        String name = folder + "/" + UUID.randomUUID() + c.ext();
        try {
            Files.createDirectories(root.resolve(folder));
        } catch (IOException e) {
            throw new BusinessException("The photo couldn't be saved. Try again.");
        }
        write(root.resolve(name), c.bytes(), "The photo couldn't be saved. Try again.");
        return name;
    }

    /** Saves a prescription (a photo or a plain PDF) privately and returns its stored name. */
    public String store(MultipartFile file) {
        Checked c = check(file, ALLOWED, "Upload a JPG, PNG or PDF file.");
        String name = UUID.randomUUID() + c.ext();
        write(root.resolve(name), c.bytes(), "The file couldn't be saved. Try again.");
        return name;
    }

    /** Deletes a private upload (prescription or return photo); a missing file is fine. */
    public void delete(String name) {
        if (name == null || name.isBlank()) return;
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root)) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    /**
     * Stored names of private uploads (prescriptions, return and ticket photos) last written before the cutoff.
     * Medicine images are public catalogue photos and never listed.
     */
    public List<String> privateFilesOlderThan(Instant cutoff) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(f -> !f.startsWith(imageRoot))
                    .filter(f -> {
                        try { return Files.getLastModifiedTime(f).toInstant().isBefore(cutoff); }
                        catch (IOException e) { return false; }
                    })
                    .map(f -> root.relativize(f).toString().replace(File.separatorChar, '/'))
                    .toList();
        }
    }

    /** Whether a private upload is still on disk: files are deleted after use, and the server's disk on restart. */
    public boolean exists(String name) {
        if (name == null || name.isBlank()) return false;
        Path p = root.resolve(name).normalize();
        return p.startsWith(root) && Files.isRegularFile(p);
    }

    public Resource load(String name) {
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw new NotFoundException("File not found.");
        return new FileSystemResource(p);
    }
}
