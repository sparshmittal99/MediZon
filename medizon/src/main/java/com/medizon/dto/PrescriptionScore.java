package com.medizon.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * How complete and trustworthy an AI-read prescription looks, out of 100, for the pharmacist and admin only. It's
 * built from checks a pharmacist would make anyway (legible, doctor and registration number, signed, recent...), so
 * they can see why it scored what it did. A high score fast-tracks the prescription to the top of the queue with
 * one-click approval; it never approves anything by itself.
 *
 * @param fastTrack at or above the threshold with no concerns raised
 */
public record PrescriptionScore(int total, List<Check> checks, List<String> concerns, boolean fastTrack) {

    /** Prescriptions older than this aren't "recent" for the date check. */
    public static final int RECENT_MONTHS = 6;

    public record Check(String label, boolean passed, int points) {}

    /** "Fast-track", "Review" or "Check carefully", for the badge. */
    public String getBand() {
        if (fastTrack) return "Fast-track";
        return total >= 50 ? "Review" : "Check carefully";
    }

    public String getBadge() {
        if (fastTrack) return "text-bg-success";
        return total >= 50 ? "text-bg-warning" : "text-bg-danger";
    }

    /**
     * @param catalogueIds ids of the products the AI was given, to tell real matches from made-up ids
     * @param threshold the score from which a prescription is fast-tracked
     */
    public static PrescriptionScore of(PrescriptionReading r, Set<Long> catalogueIds, LocalDate today, int threshold) {
        List<Check> checks = new ArrayList<>();
        List<PrescriptionReading.Line> lines = r.medicines();
        boolean readable = r.legible() && !lines.isEmpty();
        checks.add(new Check("Readable prescription with medicines", readable, 20));
        checks.add(new Check("Doctor's name", has(r.doctorName()), 10));
        checks.add(new Check("Doctor's registration number", has(r.registrationNumber()), 15));
        checks.add(new Check("Signed or stamped", r.signed(), 15));
        checks.add(new Check("Dated within the last " + RECENT_MONTHS + " months", recent(r.prescribedOnIso(), today), 15));
        checks.add(new Check("Patient's name", has(r.patientName()), 5));
        checks.add(new Check("Every medicine clearly read", readable && lines.stream().noneMatch(PrescriptionReading.Line::isUnsure), 10));
        checks.add(new Check("Every medicine found in our catalogue", readable
                && lines.stream().allMatch(l -> l.catalogId() != null && catalogueIds.contains(l.catalogId())), 10));
        int total = checks.stream().filter(Check::passed).mapToInt(Check::points).sum();
        return new PrescriptionScore(total, checks, r.concerns(), readable && total >= threshold && r.concerns().isEmpty());
    }

    static boolean recent(String iso, LocalDate today) {
        if (!has(iso)) return false;
        try {
            LocalDate d = LocalDate.parse(iso.trim());
            return !d.isAfter(today) && !d.isBefore(today.minusMonths(RECENT_MONTHS));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean has(String s) { return s != null && !s.isBlank(); }
}
