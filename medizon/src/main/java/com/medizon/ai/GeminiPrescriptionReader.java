package com.medizon.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.domain.Medicine;
import com.medizon.dto.PrescriptionReading;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/** Prescription reading with Google Gemini, through {@link GeminiClient}. */
@Component
@RequiredArgsConstructor
public class GeminiPrescriptionReader implements PrescriptionReader {

    private static final String PROMPT = """
            You read medical prescriptions for an Indian online pharmacy. The attached file is a prescription photo or PDF.
            Today is %s.
            List every medicine prescribed on it, in the order written. For each one:
            - writtenAs: the medicine as written (brand or generic, with strength if written), e.g. "Dolo 650".
            - genericName and strength: the salt and strength, when you can tell.
            - dosage: how it is taken as written, e.g. "1-0-1 after food" or "twice a day".
            - duration: e.g. "5 days"; empty if not written.
            - unitsNeeded: total tablets/capsules for the whole course when both dose and duration are clear
              (1-0-1 for 5 days = 10); null otherwise. For syrups, creams and drops use null.
            - catalogId: the id of the catalogue product below that is the same medicine. Prefer the same brand and
              strength; otherwise the same salt, strength and form. Use null if nothing in the catalogue matches.
              Never pick a different salt or strength.
            - confidence: "high" if clearly legible and matched, "medium" if partly unclear, "low" if you are guessing.
            Also, for the pharmacist who verifies it:
            - doctorName (without "Dr."), registrationNumber (the doctor's medical council registration number, empty if
              none is visible), patientName, prescribedOn (as written) and prescribedOnIso (the same date as YYYY-MM-DD,
              empty if there is no readable date).
            - signed: true if a doctor's signature or stamp is visible.
            - concerns: short notes on anything a pharmacist should look at, e.g. "date looks altered", "page is cut off",
              "looks like a screenshot of a different document", "overwritten quantity". Empty if none.
            Set legible to false and medicines to [] if the file is not a prescription or cannot be read.
            Do not invent medicines that are not on the prescription.

            Catalogue (id | name | generic | strength | form | units per pack):
            """;

    private final GeminiClient gemini;
    private final ObjectMapper json;

    @Override
    public boolean isEnabled() { return gemini.isConfigured(); }

    @Override
    public boolean isAvailable() { return gemini.isAvailable(); }

    @Override
    public PrescriptionReading read(byte[] file, String contentType, List<Medicine> catalogue) {
        StringBuilder prompt = new StringBuilder(PROMPT.formatted(LocalDate.now()));
        for (Medicine m : catalogue) {
            prompt.append(m.getId()).append(" | ").append(m.getName()).append(" | ").append(nz(m.getGenericName()))
                    .append(" | ").append(nz(m.getStrength())).append(" | ").append(nz(m.getDosageForm()))
                    .append(" | ").append(m.getUnitsPerPack()).append('\n');
        }

        ObjectNode body = json.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", prompt.toString());
        parts.addObject().putObject("inline_data")
                .put("mime_type", contentType)
                .put("data", Base64.getEncoder().encodeToString(file));
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", 0);
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", schema());

        String text = gemini.generate(body);
        try {
            return json.readValue(text, PrescriptionReading.class);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini's reading wasn't valid JSON", e);
        }
    }

    /** The JSON shape Gemini must answer in (an OpenAPI schema subset). */
    private ObjectNode schema() {
        ObjectNode line = obj("OBJECT");
        ObjectNode lp = line.putObject("properties");
        for (String f : new String[]{"writtenAs", "genericName", "strength", "dosage", "duration"}) lp.set(f, obj("STRING"));
        lp.set("unitsNeeded", obj("INTEGER").put("nullable", true));
        lp.set("catalogId", obj("INTEGER").put("nullable", true));
        ObjectNode confidence = obj("STRING");
        confidence.putArray("enum").add("high").add("medium").add("low");
        lp.set("confidence", confidence);
        line.putArray("required").add("writtenAs").add("catalogId").add("confidence");

        ObjectNode root = obj("OBJECT");
        ObjectNode p = root.putObject("properties");
        for (String f : new String[]{"doctorName", "registrationNumber", "patientName", "prescribedOn", "prescribedOnIso"}) {
            p.set(f, obj("STRING"));
        }
        p.set("legible", obj("BOOLEAN"));
        p.set("signed", obj("BOOLEAN"));
        ObjectNode concerns = obj("ARRAY");
        concerns.set("items", obj("STRING"));
        p.set("concerns", concerns);
        ObjectNode meds = obj("ARRAY");
        meds.set("items", line);
        p.set("medicines", meds);
        root.putArray("required").add("legible").add("signed").add("concerns").add("medicines");
        return root;
    }

    private ObjectNode obj(String type) { return json.createObjectNode().put("type", type); }

    private static String nz(String s) { return s == null ? "" : s; }
}
