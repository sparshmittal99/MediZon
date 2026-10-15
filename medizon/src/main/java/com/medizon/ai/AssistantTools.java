package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.PlanForm;
import com.medizon.dto.Product;
import com.medizon.repository.FeedbackRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.PrescriptionRepository;
import com.medizon.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Actions the assistant can ask for. Every call runs as the signed-in user, so it can only see and change
 * that user's own data, and each role gets its own set: customers shop and manage reminders, admins read
 * store data and reviews.
 */
@Component
@RequiredArgsConstructor
public class AssistantTools {

    private static final Set<String> CUSTOMER = Set.of("search_medicines", "medicine_details", "add_to_cart",
            "cart_summary", "my_orders", "my_plans", "todays_doses", "create_plan", "mark_dose", "refill_plan",
            "submit_feedback", "create_reminder", "list_reminders", "complete_reminder");
    private static final Set<String> ADMIN = Set.of("search_medicines", "store_tasks", "review_data",
            "create_reminder", "list_reminders", "complete_reminder");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");
    private static final int MAX_RESULTS = 8;

    private final MedicineService medicineService;
    private final CatalogService catalog;
    private final StoreCatalogService storeCatalog;
    private final AddressService addressService;
    private final CartService cartService;
    private final OrderService orderService;
    private final MedicationPlanService planService;
    private final ReorderService reorderService;
    private final MemberService memberService;
    private final ReminderService reminderService;
    private final FeedbackService feedbackService;
    private final ReviewService reviewService;
    private final OrderRepository orderRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final FeedbackRepository feedbackRepository;

    public Object execute(String name, JsonNode args, User user) {
        if (user.isStaff() && !user.isAdmin() || !(user.isAdmin() ? ADMIN : CUSTOMER).contains(name)) {
            return Map.of("error", "That isn't available for this account.");
        }
        String pin = user.isAdmin() ? null : shopperPincode(user);
        return switch (name) {
            case "search_medicines" -> catalog.products(search(args.path("query").asText(""), args.path("category").asText(""),
                    user.isAdmin(), pin), pin).stream().map(p -> summary(p, user.isAdmin())).toList();
            case "medicine_details" -> details(args, pin);
            case "add_to_cart" -> addToCart(user, args.path("medicine_id").asLong(), args.path("quantity").asInt(1), pin);
            case "cart_summary" -> cart(user, pin);
            case "my_orders" -> orderService.recentForUser(user).stream().map(this::order).toList();
            case "my_plans" -> planService.forUser(user).stream().map(this::plan).toList();
            case "todays_doses" -> planService.todaysDoses(user).stream().map(s -> Map.of(
                    "plan_id", s.getPlan().getId(),
                    "time", s.getTime().format(HH_MM),
                    "medicine", s.getPlan().getMedicine().getName(),
                    "dose", s.getPlan().getDoseLabel(),
                    "status", s.getState())).toList();
            case "create_plan" -> createPlan(user, args);
            case "mark_dose" -> markDose(user, args);
            case "refill_plan" -> refill(user, args.path("plan_id").asLong(), pin);
            case "submit_feedback" -> {
                Feedback f = feedbackService.submit(user, args.path("message").asText(""), args.path("category").asText(""),
                        args.hasNonNull("rating") ? args.get("rating").asInt() : null);
                yield Map.of("ok", true, "id", f.getId());
            }
            case "create_reminder" -> {
                Reminder r = reminderService.create(user, args.path("text").asText(""),
                        LocalDateTime.parse(args.path("due_at").asText()));
                yield Map.of("ok", true, "id", r.getId());
            }
            case "list_reminders" -> reminderService.open(user).stream().map(r -> Map.of(
                    "id", r.getId(), "text", r.getText(),
                    "due_at", r.getDueAt().truncatedTo(ChronoUnit.MINUTES).toString(),
                    "overdue", r.getDueAt().isBefore(LocalDateTime.now()))).toList();
            case "complete_reminder" -> {
                Reminder r = reminderService.complete(args.path("id").asLong(), user);
                yield Map.of("ok", true, "text", r.getText());
            }
            case "store_tasks" -> storeTasks();
            case "review_data" -> reviewData();
            default -> Map.of("error", "Unknown action " + name);
        };
    }

    // ------------------------------------------------------------------ catalog

    /** Where the customer's orders go by default: the shop, prices and stock are worked out for it. */
    private String shopperPincode(User user) {
        return addressService.defaultFor(user).map(Address::getPincode).orElse(user.getPincode());
    }

    /** Whole query first, then its longest words one at a time, then the category alone. */
    private List<Medicine> search(String query, String category, boolean admin, String pin) {
        String q = query.trim();
        String cat = medicineService.categories().stream().filter(c -> c.equalsIgnoreCase(category.trim()))
                .findFirst().orElse("");
        List<Medicine> found = find(q, cat, admin, pin);
        if (found.isEmpty() && q.contains(" ")) {
            List<String> words = new ArrayList<>(Arrays.asList(q.split("\\s+")));
            words.removeIf(w -> w.length() < 3 || w.chars().allMatch(Character::isDigit));
            words.sort(Comparator.comparingInt(String::length).reversed());
            for (String w : words) {
                found = find(w, cat, admin, pin);
                if (!found.isEmpty()) break;
            }
        }
        if (found.isEmpty() && !cat.isEmpty() && !q.isEmpty()) found = find("", cat, admin, pin);
        return found;
    }

    private List<Medicine> find(String q, String category, boolean admin, String pin) {
        if (!admin) {
            return catalog.search(q, category, 0, pin).getContent().stream().limit(MAX_RESULTS).map(Product::getMedicine).toList();
        }
        String term = q.toLowerCase();
        return medicineService.all().stream()
                .filter(m -> category.isEmpty() || category.equals(m.getCategory()))
                .filter(m -> term.isEmpty() || contains(m.getName(), term) || contains(m.getGenericName(), term)
                        || contains(m.getManufacturer(), term))
                .limit(MAX_RESULTS).toList();
    }

    private static boolean contains(String field, String term) {
        return field != null && field.toLowerCase().contains(term);
    }

    /** A product as the customer would buy it (price and stock of the store that would deliver it). */
    private Map<String, Object> summary(Product p, boolean admin) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("salt", p.getGenericName());
        out.put("manufacturer", p.getManufacturer());
        out.put("category", p.getCategory());
        out.put("price", p.getPrice());
        out.put("mrp", p.getMrp());
        out.put("pack", p.getUnitsPerPack() + " " + p.getUnitLabel());
        out.put("in_stock", p.isInStock());
        out.put("rx", p.isPrescriptionRequired());
        out.put("url", "/medicines/" + p.getId());
        out.put("image", p.getImageUrl());
        if (admin) {
            out.put("stock", p.getStock());
            out.put("active", p.isActive());
            out.put("admin_url", "/admin/medicines/" + p.getId() + "/edit");
        } else if (p.getStoreName() != null) {
            out.put("store", p.getStoreName());
        }
        return out;
    }

    private Object details(JsonNode args, String pin) {
        Medicine m;
        if (args.hasNonNull("id")) {
            m = medicineService.get(args.get("id").asLong());
        } else {
            List<Medicine> hits = search(args.path("query").asText(""), "", false, pin);
            if (hits.isEmpty()) return Map.of("error", "Not found");
            m = hits.get(0);
        }
        Map<String, Object> out = summary(catalog.product(m, pin), false);
        out.put("form", m.getDosageForm());
        out.put("description", m.getDescription());
        return out;
    }

    // ------------------------------------------------------------------ cart and orders

    private Object addToCart(User user, long medicineId, int quantity, String pin) {
        int qty = Math.max(1, Math.min(quantity, 20));
        cartService.add(user, medicineId, qty, pin);
        Medicine m = medicineService.get(medicineId);
        return Map.of("ok", true, "added", qty + " × " + m.getName(), "rx", m.isPrescriptionRequired(),
                "cart_count", cartService.count(user));
    }

    private Object cart(User user, String pin) {
        CartSummary c = cartService.summary(user, pin);
        List<Map<String, Object>> items = c.getItems().stream().map(i -> Map.<String, Object>of(
                "name", i.getMedicine().getName(), "quantity", i.getQuantity(), "line_total", i.getLineTotal(),
                "for", i.getSortedMembers().stream().map(Member::getLabel).toList())).toList();
        return Map.of("items", items, "subtotal", c.getSubtotal(), "delivery", c.getDeliveryFee(),
                "total", c.getTotal(), "needs_prescription", c.isPrescriptionRequired());
    }

    private Map<String, Object> order(Order o) {
        return Map.of("id", o.getId(), "order_number", o.getOrderNumber(), "status", o.getStatus().getLabel(),
                "total", o.getTotal(), "placed", o.getCreatedAt().format(DAY),
                "items", o.getItems().stream().map(i -> i.getQuantity() + " × " + i.getMedicineName()).toList(),
                "url", "/orders/" + o.getId());
    }

    // ------------------------------------------------------------------ medication plans

    private Map<String, Object> plan(MedicationPlan p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("medicine", p.getMedicine().getName());
        out.put("medicine_id", p.getMedicine().getId());
        out.put("for", p.getMember() == null ? "Me" : p.getMember().getLabel());
        out.put("needs_setup", p.isSetupRequired());
        out.put("active", p.isActive());
        out.put("dose", p.getDoseLabel());
        out.put("times", p.getReminderTimes().stream().map(t -> t.format(HH_MM)).toList());
        out.put("days_left", p.getDaysLeftRounded());
        out.put("running_low", p.isRunningLow());
        return out;
    }

    private Object createPlan(User user, JsonNode args) {
        Medicine m = medicineService.get(args.path("medicine_id").asLong());
        Member self = memberService.self(user);
        boolean exists = planService.forUser(user).stream()
                .anyMatch(p -> p.getMember() != null && p.getMember().getId().equals(self.getId())
                        && p.getMedicine().getId().equals(m.getId()));
        if (exists) {
            return Map.of("error", "You already have reminders for " + m.getName()
                    + ". You can change the times in My medicines.");
        }
        List<String> times = new ArrayList<>();
        args.path("times").forEach(t -> times.add(t.asText()));
        if (times.isEmpty() || times.size() > 6) return Map.of("error", "Give between 1 and 6 reminder times.");

        PlanForm form = new PlanForm();
        form.setMemberId(self.getId());
        form.setMedicineId(m.getId());
        form.setDosePerIntake(Math.max(1, Math.min(args.path("dose").asInt(1), 10)));
        form.setTimes(times);
        int units = args.path("units_remaining").asInt(-1);
        form.setUnitsRemaining(units < 0 ? m.getUnitsPerPack() : Math.min(units, 10_000));
        form.setReorderMode(ReorderMode.MANUAL);
        MedicationPlan p = planService.save(user, form);
        reorderService.evaluate(p.getId());
        return Map.of("ok", true, "plan_id", p.getId(), "medicine", m.getName(), "dose", p.getDoseLabel(),
                "times", p.getReminderTimes().stream().map(t -> t.format(HH_MM)).toList(),
                "days_left", p.getDaysLeftRounded());
    }

    private Object markDose(User user, JsonNode args) {
        LocalTime time = LocalTime.parse(args.path("time").asText());
        DoseStatus status = "SKIPPED".equalsIgnoreCase(args.path("status").asText()) ? DoseStatus.SKIPPED : DoseStatus.TAKEN;
        MedicationPlan p = planService.respond(user, args.path("plan_id").asLong(), time, status);
        reorderService.evaluate(p.getId());
        return Map.of("ok", true, "medicine", p.getMedicine().getName(), "time", time.format(HH_MM),
                "units_left", p.getUnitsRemaining());
    }

    private Object refill(User user, long planId, String pin) {
        MedicationPlan p = planService.get(planId, user);
        cartService.add(user, p.getMedicine().getId(), p.getReorderPacks(), List.of(p.getMember()), pin);
        return Map.of("ok", true, "added", p.getReorderPacks() + " pack(s) of " + p.getTitle(),
                "cart_count", cartService.count(user));
    }

    // ------------------------------------------------------------------ admin

    private Object storeTasks() {
        LocalDate today = LocalDate.now();
        LocalDateTime weekAgo = today.minusDays(6).atStartOfDay();
        long lowReviews = reviewService.list(null).stream()
                .filter(r -> r.getRating() <= 2 && r.getCreatedAt().isAfter(weekAgo)).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders_to_confirm", orderRepository.countByUserRoleAndStatus(Role.CUSTOMER, OrderStatus.PLACED));
        out.put("orders_to_ship", orderRepository.countByUserRoleAndStatus(Role.CUSTOMER, OrderStatus.CONFIRMED));
        out.put("orders_in_transit", orderRepository.countByUserRoleAndStatus(Role.CUSTOMER, OrderStatus.SHIPPED));
        out.put("prescriptions_pending", prescriptionRepository.countByStatus(PrescriptionStatus.PENDING));
        out.put("low_stock", storeCatalog.lowStock(null).stream()
                .map(i -> Map.of("id", i.getMedicine().getId(), "name", i.getMedicine().getName(), "store", i.getStore().getName(),
                        "stock", i.getStock())).toList());
        out.put("low_reviews_7d", lowReviews);
        out.put("feedback_7d", feedbackRepository.countByCreatedAtAfter(weekAgo));
        out.put("orders_today", orderRepository.countByCreatedAtAfter(today.atStartOfDay()));
        out.put("revenue_today", orderRepository.revenueSince(today.atStartOfDay(), OrderStatus.CANCELLED));
        return out;
    }

    private Object reviewData() {
        List<Map<String, Object>> reviews = reviewService.list(null).stream().limit(500).map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("rating", r.getRating());
            m.put("comment", r.getComment());
            m.put("date", r.getCreatedAt().toString());
            return m;
        }).toList();
        List<Map<String, Object>> feedback = feedbackService.all().stream().limit(500).map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("message", f.getMessage());
            m.put("rating", f.getRating());
            m.put("category", f.getCategory());
            m.put("date", f.getCreatedAt().toString());
            return m;
        }).toList();
        return Map.of("reviews", reviews, "feedback", feedback);
    }
}
