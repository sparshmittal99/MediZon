package com.medizon.ai;

import com.medizon.domain.Medicine;
import com.medizon.dto.PrescriptionReading;

import java.util.List;

/**
 * Reads a prescription photo or PDF with an AI model and matches what's written to our catalogue. One implementation
 * per provider; {@code medizon.ai.provider} picks which one is used.
 */
public interface PrescriptionReader {

    /** Chosen by {@code medizon.ai.provider} and has what it needs (an API key...). */
    boolean isEnabled();

    /** Enabled and not known to be down right now; when false, prescriptions go straight to the pharmacist. */
    boolean isAvailable();

    PrescriptionReading read(byte[] file, String contentType, List<Medicine> catalogue);
}
