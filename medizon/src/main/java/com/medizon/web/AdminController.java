package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.DayTraffic;
import com.medizon.dto.NewUserForm;
import com.medizon.dto.PeriodFilter;
import com.medizon.dto.PrescriptionReading;
import com.medizon.dto.PrescriptionScore;
import com.medizon.dto.ReportPeriod;
import com.medizon.dto.SalesDay;
import com.medizon.repository.*;
import com.medizon.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The back office. The admin sees everything; every other staff role lands on its own page and only reaches what its
 * role allows (see SecurityConfig). Store accounts only see their own store's orders.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MedicineService medicineService;
    private final OrderService orderService;
    private final PrescriptionService prescriptionService;
    private final PrescriptionScanService scanService;
    private final OrderRepository orderRepository;
    private final MedicineRepository medicineRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicationPlanRepository planRepository;
    private final UserRepository userRepository;
    private final DailyVisitRepository visitRepository;
    private final UserService userService;
    private final ReviewService reviewService;
    private final FeedbackService feedbackService;
    private final InvoiceService invoiceService;
    private final ReturnService returnService;
    private final SalesReportService salesReport;
    private final SalesExcelService salesExcel;
    private final DeliveryAreaService deliveryAreas;
    private final StoreService storeService;
    private final StoreCatalogService storeCatalog;
    private final AccountDeletionService accountDeletion;
    private final CurrentUser currentUser;

    /** The admin's overview; other staff go straight to their own work. */
    @GetMapping
    public String dashboard(@RequestParam(required = false) String denied,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate storeDay,
                            Model model, RedirectAttributes ra) {
        User me = currentUser.get();
        if (!me.isAdmin()) {
            if (denied != null) ra.addFlashAttribute("error", "That page isn't part of your role.");
            return "redirect:" + switch (me.getRole()) {
                case PHARMACIST -> "/admin/prescriptions";
                case SUPPORT -> "/admin/reviews";
                case FINANCE -> "/admin/sales";
                case STORE -> "/admin/orders";
                default -> "/";
            };
        }
        if (denied != null) model.addAttribute("error", "That page isn't available to your account.");
        model.addAttribute("toConfirm", orderRepository.countByUserRoleAndStatus(Role.CUSTOMER, OrderStatus.PLACED));
        model.addAttribute("revenue", orderRepository.revenueExcluding(OrderStatus.CANCELLED).subtract(returnService.totalRefunded()));
        model.addAttribute("refunded", returnService.totalRefunded());
        model.addAttribute("returnsToReview", returnService.countAwaitingReview());
        model.addAttribute("today", salesReport.day(LocalDate.now()));
        model.addAttribute("pendingRx", prescriptionRepository.countByStatus(PrescriptionStatus.PENDING));
        model.addAttribute("autoOrders", orderRepository.countBySource(OrderSource.AUTO_REORDER));
        model.addAttribute("activePlans", planRepository.countByActiveTrue());
        model.addAttribute("recentOrders", orderRepository.findTop8ByUserRoleOrderByCreatedAtDesc(Role.CUSTOMER));
        model.addAttribute("lowStock", storeCatalog.lowStock(null));
        model.addAttribute("deliveryAreas", deliveryAreas.activeCount());
        model.addAttribute("stores", storeService.all());

        // Each store's sales on one day (today unless the admin picks another), to compare stores day by day
        LocalDate day = storeDay == null || storeDay.isAfter(LocalDate.now()) ? LocalDate.now() : storeDay;
        List<SalesReportService.StoreSales> byStore = salesReport.byStore(day, day);
        SalesDay dayTotal = SalesDay.total(byStore.stream().map(SalesReportService.StoreSales::totals).toList());
        model.addAttribute("storeDay", day);
        model.addAttribute("storeDayIsToday", day.equals(LocalDate.now()));
        model.addAttribute("storeSales", byStore);
        model.addAttribute("storeDayTotal", dayTotal);

        // people and traffic
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);
        model.addAttribute("signupsToday", userRepository.countByRoleAndCreatedAtAfter(Role.CUSTOMER, today.atStartOfDay()));
        model.addAttribute("signupsWeek", userRepository.countByRoleAndCreatedAtAfter(Role.CUSTOMER, weekStart.atStartOfDay()));
        model.addAttribute("customers", userRepository.countByRole(Role.CUSTOMER));
        model.addAttribute("onlineNow", userService.onlineEmails().size());
        model.addAttribute("visitsToday", visitRepository.findById(today).map(DailyVisit::getVisits).orElse(0L));
        model.addAttribute("visitsWeek", visitRepository.visitsSince(weekStart));
        model.addAttribute("visitsTotal", visitRepository.totalVisits());
        model.addAttribute("traffic", lastSevenDays(weekStart));
        model.addAttribute("recentSignups", userRepository.findTop6ByRoleOrderByCreatedAtDesc(Role.CUSTOMER));
        model.addAttribute("avgRating", reviewService.averageRating());
        model.addAttribute("reviewCount", reviewService.count());
        model.addAttribute("active", "dashboard");
        return "admin/dashboard";
    }

    private List<DayTraffic> lastSevenDays(LocalDate from) {
        Map<LocalDate, DailyVisit> visits = visitRepository.findByDayGreaterThanEqualOrderByDayDesc(from).stream()
                .collect(Collectors.toMap(DailyVisit::getDay, Function.identity()));
        Map<LocalDate, Long> signups = userRepository.findByCreatedAtAfter(from.atStartOfDay()).stream()
                .filter(u -> u.getRole() == Role.CUSTOMER && u.getCreatedAt() != null)
                .collect(Collectors.groupingBy(u -> u.getCreatedAt().toLocalDate(), Collectors.counting()));
        List<DayTraffic> rows = new ArrayList<>();
        for (LocalDate d = LocalDate.now(); !d.isBefore(from); d = d.minusDays(1)) {
            DailyVisit v = visits.get(d);
            rows.add(new DayTraffic(d, v == null ? 0 : v.getVisits(), v == null ? 0 : v.getPageViews(),
                    signups.getOrDefault(d, 0L)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- sales report

    /**
     * Sales, orders and returns for a date range, a month or a financial year, shown day by day or month by month.
     * Defaults to the last 30 days; financial years and long ranges default to month-wise. With a store chosen every
     * figure is that store's alone; without one, a store-by-store table compares them over the same period.
     */
    @GetMapping("/sales")
    public String sales(@RequestParam(defaultValue = "range") String period,
                        @RequestParam(required = false) Long store,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                        @RequestParam(required = false) String fy,
                        @RequestParam(required = false) String group,
                        @RequestParam(defaultValue = "false") boolean hideEmpty, Model model) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        Store scope = store == null ? null : storeService.get(store);
        boolean monthly = groupMonthly(p, group);
        List<SalesDay> rows = new ArrayList<>(monthly ? salesReport.monthly(p.getFrom(), p.getTo(), store) : salesReport.daily(p.getFrom(), p.getTo(), store));
        Collections.reverse(rows);   // newest first on screen
        LocalDate today = LocalDate.now();
        String thisFy = SalesReportService.financialYear(today);

        model.addAttribute("p", p);
        model.addAttribute("monthly", monthly);
        model.addAttribute("dailyAllowed", p.getDays() <= SalesReportService.MAX_DAILY_DAYS);
        model.addAttribute("hideEmpty", hideEmpty);
        model.addAttribute("rows", hideEmpty ? rows.stream().filter(d -> !d.isEmpty()).toList() : rows);
        model.addAttribute("period", SalesDay.total(rows));
        model.addAttribute("today", salesReport.day(today, store));
        model.addAttribute("allTime", salesReport.allTime(store));
        model.addAttribute("store", scope);
        model.addAttribute("stores", storeService.all());
        if (scope == null) model.addAttribute("byStore", salesReport.byStore(p.getFrom(), p.getTo()));
        model.addAttribute("fys", salesReport.financialYears());
        model.addAttribute("thisFy", thisFy);
        model.addAttribute("lastFy", SalesReportService.financialYear(today.minusYears(1)));
        model.addAttribute("thisMonth", YearMonth.from(today));
        model.addAttribute("lastMonth", YearMonth.from(today).minusMonths(1));
        model.addAttribute("last7", today.minusDays(6));
        model.addAttribute("last30", today.minusDays(29));
        model.addAttribute("last90", today.minusDays(89));
        model.addAttribute("sections", SalesExcelService.Section.values());
        model.addAttribute("active", "sales");
        return "admin/sales";
    }

    /** Excel workbook for the same period: a summary sheet plus the chosen sections (all of them if none are ticked). */
    @GetMapping("/sales.xlsx")
    public ResponseEntity<byte[]> salesExcel(
            @RequestParam(defaultValue = "range") String period,
            @RequestParam(required = false) Long store,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String fy,
            @RequestParam(required = false) List<SalesExcelService.Section> sheets) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        Store scope = store == null ? null : storeService.get(store);
        Set<SalesExcelService.Section> chosen = sheets == null || sheets.isEmpty()
                ? EnumSet.allOf(SalesExcelService.Section.class) : EnumSet.copyOf(sheets);
        // Built in memory: even a busy financial year is a few MB
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            salesExcel.write(p, scope, chosen, buffer);
        } catch (IOException e) {
            throw new BusinessException("The Excel file couldn't be created. Try again.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"medizon-sales-" + storeSuffix(scope) + p.getFileSuffix() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(buffer.toByteArray());
    }

    @GetMapping(value = "/sales.csv", produces = "text/csv")
    public ResponseEntity<String> salesCsv(
            @RequestParam(defaultValue = "range") String period,
            @RequestParam(required = false) Long store,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String fy,
            @RequestParam(required = false) String group) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        Store scope = store == null ? null : storeService.get(store);
        List<SalesDay> rows = groupMonthly(p, group) ? salesReport.monthly(p.getFrom(), p.getTo(), store) : salesReport.daily(p.getFrom(), p.getTo(), store);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"medizon-sales-" + storeSuffix(scope) + p.getFileSuffix() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(salesReport.csv(rows, SalesDay.total(rows)));
    }

    /** "apollo-sector-14-" for a store's export file names; nothing for all stores. */
    private static String storeSuffix(Store s) {
        if (s == null) return "";
        String slug = s.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "store-" + s.getId() + "-" : slug + "-";
    }

    /** Month-wise when asked, for financial years and ranges over 3 months by default, and always past a year. */
    private static boolean groupMonthly(ReportPeriod p, String group) {
        if (p.getDays() > SalesReportService.MAX_DAILY_DAYS) return true;
        if ("month".equals(group)) return true;
        if ("day".equals(group)) return false;
        return p.getKind() == ReportPeriod.Kind.FY || p.getDays() > 92;
    }

    // ---------------------------------------------------------------- users

    @GetMapping("/users")
    public String users(@RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "all") String show, Model model) {
        Set<String> online = userService.onlineEmails();
        LocalDate weekStart = LocalDate.now().minusDays(6);
        String term = q == null ? "" : q.trim().toLowerCase();

        List<User> list = userService.all().stream()
                .filter(u -> term.isEmpty()
                        || u.getFullName().toLowerCase().contains(term)
                        || u.getEmail().toLowerCase().contains(term)
                        || (u.getPhone() != null && u.getPhone().contains(term)))
                .filter(u -> switch (show) {
                    case "customers" -> u.getRole() == Role.CUSTOMER;
                    case "admin" -> u.isAdmin();
                    case "staff" -> u.isStaff() && !u.isAdmin();   // the admin is listed under Admin, not Staff
                    case "disabled" -> !u.isEnabled();
                    case "online" -> online.contains(u.getEmail().toLowerCase());
                    case "new" -> u.getCreatedAt() != null && !u.getCreatedAt().toLocalDate().isBefore(weekStart);
                    default -> true;
                })
                .toList();

        Map<Long, Long> orderCounts = new HashMap<>();
        for (Object[] row : orderRepository.countPerUser()) orderCounts.put((Long) row[0], (Long) row[1]);

        model.addAttribute("users", list);
        model.addAttribute("online", online);
        model.addAttribute("orderCounts", orderCounts);
        model.addAttribute("newSince", weekStart.atStartOfDay());
        model.addAttribute("q", q);
        model.addAttribute("show", show);
        model.addAttribute("roles", Role.assignable());
        model.addAttribute("stores", storeService.all());
        model.addAttribute("active", "users");
        return "admin/users";
    }

    @PostMapping("/users/{id}/enabled")
    public String setEnabled(@PathVariable Long id, @RequestParam boolean enabled, RedirectAttributes ra) {
        User u = userService.setEnabled(id, enabled, currentUser.get());
        ra.addFlashAttribute("success", u.getFullName()
                + (enabled ? "'s account is active again." : "'s account is disabled and signed out."));
        return "redirect:/admin/users";
    }

    /** The admin creates an account with a role: a customer, or staff limited to that role's pages. */
    @PostMapping("/users")
    public String createUser(@Valid @ModelAttribute("userForm") NewUserForm form, BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute("error", result.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/users";
        }
        User u = userService.createUser(form, currentUser.get());
        ra.addFlashAttribute("success", u.getRole().getLabel() + " account created for " + u.getFullName() + " (" + u.getEmail() + ")"
                + (u.getStore() != null ? " at " + u.getStore().getName() : "") + ".");
        return "redirect:/admin/users" + (u.isStaff() ? "?show=staff" : "");
    }

    /** Deletes an account; orders and invoices stay for the accounts without the personal details. */
    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id, RedirectAttributes ra) {
        String name = accountDeletion.deleteByAdmin(id, currentUser.get());
        ra.addFlashAttribute("success", name + "'s account is deleted. Their orders and invoices are kept for the accounts.");
        return "redirect:/admin/users";
    }

    /** Sets a new password for the account (no current password needed) and signs it out everywhere. */
    @PostMapping("/users/{id}/password")
    public String setPassword(@PathVariable Long id, @RequestParam String newPassword, @RequestParam String confirmPassword,
                              RedirectAttributes ra) {
        User u = userService.setPasswordByAdmin(id, newPassword, confirmPassword, currentUser.get());
        ra.addFlashAttribute("success", u.getFullName() + "'s password is changed and they're signed out everywhere. Let them know the new one.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/sign-out")
    public String signOut(@PathVariable Long id, RedirectAttributes ra) {
        User u = userService.get(id);
        if (u.getId().equals(currentUser.get().getId())) {
            throw new BusinessException("Use Sign out in the menu to end your own session.");
        }
        int n = userService.signOutEverywhere(u);
        ra.addFlashAttribute("success", n == 0 ? u.getFullName() + " wasn't signed in."
                : u.getFullName() + " was signed out of " + n + " session" + (n == 1 ? "." : "s."));
        return "redirect:/admin/users";
    }

    // ---------------------------------------------------------------- delivery areas

    /** PIN codes, and which stores deliver to each, quickest first. */
    @GetMapping("/locations")
    public String locations(Model model) {
        model.addAttribute("pincodes", deliveryAreas.all());
        model.addAttribute("activeCount", deliveryAreas.activeCount());
        model.addAttribute("servedBy", storeService.areasByPincode());
        model.addAttribute("stores", storeService.all());
        model.addAttribute("active", "locations");
        return "admin/locations";
    }

    /** Adds PIN codes, and with a store chosen, has that store deliver there. */
    @PostMapping("/locations")
    public String addLocations(@RequestParam String pincodes, @RequestParam(required = false) String area,
                               @RequestParam(required = false) String city, @RequestParam(required = false) String state,
                               @RequestParam(required = false) Long storeId,
                               @RequestParam(defaultValue = "1") int minDays, @RequestParam(defaultValue = "3") int maxDays,
                               RedirectAttributes ra) {
        int n = deliveryAreas.add(pincodes, area, city, state);
        if (storeId != null) storeService.serve(storeId, pincodes, minDays, maxDays);
        ra.addFlashAttribute("success", (n == 1 ? "PIN code saved." : n + " PIN codes saved.")
                + (storeId != null ? " " + storeService.get(storeId).getName() + " delivers there now." : " Choose a store to deliver there."));
        return "redirect:/admin/locations";
    }

    /** One store starts delivering to a PIN code, or changes how long it takes there. */
    @PostMapping("/locations/serve")
    public String serve(@RequestParam Long storeId, @RequestParam String pincode, @RequestParam int minDays,
                        @RequestParam int maxDays, RedirectAttributes ra) {
        storeService.serve(storeId, pincode, minDays, maxDays);
        ra.addFlashAttribute("success", storeService.get(storeId).getName() + " delivers to " + pincode + ".");
        return "redirect:/admin/locations";
    }

    @PostMapping("/locations/areas/{areaId}/delete")
    public String stopServing(@PathVariable Long areaId, RedirectAttributes ra) {
        StoreArea a = storeService.stopServing(areaId);
        ra.addFlashAttribute("success", a.getStore().getName() + " no longer delivers to " + a.getPincode() + ".");
        return "redirect:/admin/locations";
    }

    @PostMapping("/locations/{id}/active")
    public String setLocationActive(@PathVariable Long id, @RequestParam boolean active, RedirectAttributes ra) {
        ServiceablePincode p = deliveryAreas.setActive(id, active);
        ra.addFlashAttribute("success", active ? "Delivering to " + p.getPincode() + " again." : "Deliveries to " + p.getPincode() + " paused.");
        return "redirect:/admin/locations";
    }

    @PostMapping("/locations/{id}/delete")
    public String deleteLocation(@PathVariable Long id, RedirectAttributes ra) {
        ServiceablePincode p = deliveryAreas.delete(id);
        ra.addFlashAttribute("success", p.getPincode() + " removed from delivery areas.");
        return "redirect:/admin/locations";
    }

    // ---------------------------------------------------------------- reviews

    @GetMapping("/reviews")
    public String reviews(@RequestParam(required = false) Integer rating, Model model) {
        model.addAttribute("reviews", reviewService.list(rating));
        model.addAttribute("feedback", feedbackService.all());
        model.addAttribute("avgRating", reviewService.averageRating());
        model.addAttribute("reviewCount", reviewService.count());
        model.addAttribute("distribution", reviewService.distribution());
        model.addAttribute("selected", rating);
        model.addAttribute("active", "reviews");
        return "admin/reviews";
    }

    // ---------------------------------------------------------------- orders

    /**
     * Orders: a store account sees its own store's; everyone else every store's, optionally filtered by store. The
     * newest 20 placed in the chosen years, months or days (this year by default), or found by order number.
     */
    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) OrderStatus status, @RequestParam(required = false) Long store,
                         @RequestParam(required = false) List<String> year, @RequestParam(required = false) List<String> month,
                         @RequestParam(required = false) List<String> date, @RequestParam(required = false) String q,
                         Model model) {
        User me = currentUser.get();
        Store scope = storeService.scopeOf(me).orElse(store == null ? null : storeService.get(store));
        PeriodFilter period = PeriodFilter.of(year, month, date);
        model.addAttribute("orders", orderService.adminList(status, scope, period, q));
        model.addAttribute("counts", orderService.adminCounts(scope, period, q));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selected", status);
        model.addAttribute("store", scope);
        model.addAttribute("stores", me.isStoreStaff() ? List.of() : storeService.all());
        model.addAttribute("canManage", me.isAdmin() || me.isStoreStaff());
        model.addAttribute("period", period);
        model.addAttribute("periodYears", period.yearsSince(orderService.firstOrderYear(null)));
        model.addAttribute("q", PeriodFilter.term(q));
        model.addAttribute("limit", PeriodFilter.LIMIT);
        model.addAttribute("active", "orders");
        return "admin/orders";
    }

    @GetMapping("/orders/{id}")
    public String order(@PathVariable Long id, Model model) {
        User me = currentUser.get();
        Order o = orderService.get(id);
        storeService.checkAccess(me, o);
        model.addAttribute("o", o);
        model.addAttribute("placedWith", orderService.placedWith(o));
        model.addAttribute("canManage", me.isAdmin() || me.isStoreStaff());
        model.addAttribute("review", reviewService.forOrder(o).orElse(null));
        model.addAttribute("returns", returnService.forOrder(o));
        model.addAttribute("adminView", true);
        return "orders/detail";
    }

    @GetMapping("/orders/{id}/invoice")
    public String invoice(@PathVariable Long id, Model model) {
        Order o = orderService.get(id);
        storeService.checkAccess(currentUser.get(), o);
        model.addAttribute("inv", invoiceService.forOrder(o));
        model.addAttribute("adminView", true);
        model.addAttribute("active", "orders");
        return "orders/invoice";
    }

    @PostMapping("/orders/{id}/status")
    public String updateStatus(@PathVariable Long id, @RequestParam OrderStatus status,
                               @RequestHeader(value = "Referer", required = false) String referer,
                               RedirectAttributes ra) {
        storeService.checkAccess(currentUser.get(), orderService.get(id));
        Order o = orderService.updateStatus(id, status);
        ra.addFlashAttribute("success", "Order " + o.getOrderNumber() + " marked " + status.getLabel().toLowerCase() + ".");
        return "redirect:" + (referer != null ? referer : "/admin/orders");
    }

    // ---------------------------------------------------------------- prescriptions

    @GetMapping("/prescriptions")
    public String prescriptions(Model model) {
        List<Prescription> pending = prescriptionService.pending();
        model.addAttribute("pending", pending);
        // The AI's reading and score of each, for the pharmacist and admin only
        Map<Long, PrescriptionScore> scores = new HashMap<>();
        Map<Long, PrescriptionReading> readings = new HashMap<>();
        Map<Long, List<PrescriptionScanService.CoverRow>> covers = new HashMap<>();
        pending.forEach(p -> {
            scanService.score(p).ifPresent(sc -> scores.put(p.getId(), sc));
            scanService.reading(p).ifPresent(r -> readings.put(p.getId(), r));
            covers.put(p.getId(), scanService.suggestedCover(p));     // pre-filled from the AI reading
        });
        model.addAttribute("scores", scores);
        model.addAttribute("readings", readings);
        model.addAttribute("covers", covers);
        model.addAttribute("catalogue", medicineRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("maxPacks", PrescriptionService.MAX_PACKS);
        model.addAttribute("reviewed", prescriptionService.recentlyReviewed());
        model.addAttribute("defaultValidUntil", LocalDate.now().plusMonths(6));
        model.addAttribute("active", "prescriptions");
        return "admin/prescriptions";
    }

    @PostMapping("/prescriptions/{id}/review")
    public String review(@PathVariable Long id, @RequestParam boolean approve,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntil,
                         @RequestParam(name = "itemMedicine", required = false) List<String> itemMedicine,
                         @RequestParam(name = "itemPacks", required = false) List<String> itemPacks,
                         @RequestParam(required = false) String doctorName,
                         @RequestParam(required = false) String patientName,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate prescribedOn,
                         RedirectAttributes ra) {
        prescriptionService.review(id, approve, note, validUntil, coverFromForm(itemMedicine, itemPacks),
                new PrescriptionService.Details(doctorName, patientName, prescribedOn));
        ra.addFlashAttribute("success", approve ? "Prescription approved." : "Prescription rejected.");
        return "redirect:/admin/prescriptions";
    }

    /** The approval form's medicine rows (a medicine and its packs per row) as packs by medicine; blank rows skipped. */
    static Map<Long, Integer> coverFromForm(List<String> medicineIds, List<String> packs) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (medicineIds == null) return out;
        for (int k = 0; k < medicineIds.size(); k++) {
            String id = medicineIds.get(k);
            if (id == null || id.isBlank()) continue;
            String n = packs != null && k < packs.size() ? packs.get(k) : null;
            int qty;
            try {
                qty = n == null || n.isBlank() ? 0 : Integer.parseInt(n.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("Enter the packs as a whole number.");
            }
            out.merge(Long.parseLong(id.trim()), qty, Integer::sum);
        }
        return out;
    }
}
