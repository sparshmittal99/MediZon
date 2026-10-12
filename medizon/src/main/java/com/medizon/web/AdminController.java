package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.AdminForm;
import com.medizon.dto.DayTraffic;
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

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MedicineService medicineService;
    private final OrderService orderService;
    private final PrescriptionService prescriptionService;
    private final OrderRepository orderRepository;
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
    private final CurrentUser currentUser;

    /** The image is chosen through upload / link fields, never posted directly. */
    @InitBinder("medicine")
    void medicineBinder(WebDataBinder binder) { binder.setDisallowedFields("imageUrl"); }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("toConfirm", orderRepository.countByUserRoleAndStatus(Role.CUSTOMER, OrderStatus.PLACED));
        model.addAttribute("revenue", orderRepository.revenueExcluding(OrderStatus.CANCELLED).subtract(returnService.totalRefunded()));
        model.addAttribute("refunded", returnService.totalRefunded());
        model.addAttribute("returnsToReview", returnService.countAwaitingReview());
        model.addAttribute("today", salesReport.day(LocalDate.now()));
        model.addAttribute("pendingRx", prescriptionRepository.countByStatus(PrescriptionStatus.PENDING));
        model.addAttribute("autoOrders", orderRepository.countBySource(OrderSource.AUTO_REORDER));
        model.addAttribute("activePlans", planRepository.countByActiveTrue());
        model.addAttribute("recentOrders", orderRepository.findTop8ByUserRoleOrderByCreatedAtDesc(Role.CUSTOMER));
        model.addAttribute("lowStock", medicineService.lowStock(MedicineService.LOW_STOCK_BELOW));

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
     * Defaults to the last 30 days; financial years and long ranges default to month-wise.
     */
    @GetMapping("/sales")
    public String sales(@RequestParam(defaultValue = "range") String period,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                        @RequestParam(required = false) String fy,
                        @RequestParam(required = false) String group,
                        @RequestParam(defaultValue = "false") boolean hideEmpty, Model model) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        boolean monthly = groupMonthly(p, group);
        List<SalesDay> rows = new ArrayList<>(monthly ? salesReport.monthly(p.getFrom(), p.getTo()) : salesReport.daily(p.getFrom(), p.getTo()));
        Collections.reverse(rows);   // newest first on screen
        LocalDate today = LocalDate.now();
        String thisFy = SalesReportService.financialYear(today);

        model.addAttribute("p", p);
        model.addAttribute("monthly", monthly);
        model.addAttribute("dailyAllowed", p.getDays() <= SalesReportService.MAX_DAILY_DAYS);
        model.addAttribute("hideEmpty", hideEmpty);
        model.addAttribute("rows", hideEmpty ? rows.stream().filter(d -> !d.isEmpty()).toList() : rows);
        model.addAttribute("period", SalesDay.total(rows));
        model.addAttribute("today", salesReport.day(today));
        model.addAttribute("allTime", salesReport.allTime());
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String fy,
            @RequestParam(required = false) List<SalesExcelService.Section> sheets) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        Set<SalesExcelService.Section> chosen = sheets == null || sheets.isEmpty()
                ? EnumSet.allOf(SalesExcelService.Section.class) : EnumSet.copyOf(sheets);
        // Built in memory: even a busy financial year is a few MB
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            salesExcel.write(p, chosen, buffer);
        } catch (IOException e) {
            throw new BusinessException("The Excel file couldn't be created. Try again.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"medizon-sales-" + p.getFileSuffix() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(buffer.toByteArray());
    }

    @GetMapping(value = "/sales.csv", produces = "text/csv")
    public ResponseEntity<String> salesCsv(
            @RequestParam(defaultValue = "range") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String fy,
            @RequestParam(required = false) String group) {
        ReportPeriod p = salesReport.resolve(period, from, to, month, fy);
        List<SalesDay> rows = groupMonthly(p, group) ? salesReport.monthly(p.getFrom(), p.getTo()) : salesReport.daily(p.getFrom(), p.getTo());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"medizon-sales-" + p.getFileSuffix() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(salesReport.csv(rows, SalesDay.total(rows)));
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
                    case "admins" -> u.getRole() == Role.ADMIN;
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

    @PostMapping("/users/admins")
    public String createAdmin(@Valid @ModelAttribute("adminForm") AdminForm form, BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute("error", result.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/users?show=admins";
        }
        User u = userService.createAdmin(form, currentUser.get());
        ra.addFlashAttribute("success", "Admin account created for " + u.getFullName() + " (" + u.getEmail() + ").");
        return "redirect:/admin/users?show=admins";
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

    // ---------------------------------------------------------------- medicines

    @GetMapping("/medicines")
    public String medicines(Model model) {
        model.addAttribute("medicines", medicineService.all());
        model.addAttribute("active", "medicines");
        return "admin/medicines";
    }

    @GetMapping("/medicines/new")
    public String newMedicine(Model model) {
        return medicineForm(new Medicine(), model);
    }

    @GetMapping("/medicines/{id}/edit")
    public String editMedicine(@PathVariable Long id, Model model) {
        return medicineForm(medicineService.get(id), model);
    }

    private String medicineForm(Medicine m, Model model) {
        model.addAttribute("medicine", m);
        model.addAttribute("categories", medicineService.categories());
        model.addAttribute("active", "medicines");
        return "admin/medicine-form";
    }

    @PostMapping("/medicines/save")
    public String saveMedicine(@Valid @ModelAttribute("medicine") Medicine medicine, BindingResult result,
                               @RequestParam(required = false) MultipartFile imageFile,
                               @RequestParam(required = false) String imageLink,
                               @RequestParam(defaultValue = "false") boolean removeImage,
                               Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            medicine.setImageUrl(medicineService.currentImage(medicine.getId()));
            model.addAttribute("categories", medicineService.categories());
            model.addAttribute("active", "medicines");
            return "admin/medicine-form";
        }
        medicineService.save(medicine, imageFile, imageLink, removeImage);
        ra.addFlashAttribute("success", medicine.getName() + " saved.");
        return "redirect:/admin/medicines";
    }

    @PostMapping("/medicines/{id}/toggle")
    public String toggleMedicine(@PathVariable Long id) {
        medicineService.toggleActive(id);
        return "redirect:/admin/medicines";
    }

    // ---------------------------------------------------------------- orders

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) OrderStatus status, Model model) {
        model.addAttribute("orders", orderService.adminList(status));
        model.addAttribute("counts", orderService.adminCounts());
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selected", status);
        model.addAttribute("active", "orders");
        return "admin/orders";
    }

    @GetMapping("/orders/{id}")
    public String order(@PathVariable Long id, Model model) {
        Order o = orderService.get(id);
        model.addAttribute("o", o);
        model.addAttribute("review", reviewService.forOrder(o).orElse(null));
        model.addAttribute("returns", returnService.forOrder(o));
        model.addAttribute("adminView", true);
        return "orders/detail";
    }

    @GetMapping("/orders/{id}/invoice")
    public String invoice(@PathVariable Long id, Model model) {
        model.addAttribute("inv", invoiceService.forOrder(orderService.get(id)));
        model.addAttribute("adminView", true);
        model.addAttribute("active", "orders");
        return "orders/invoice";
    }

    @PostMapping("/orders/{id}/status")
    public String updateStatus(@PathVariable Long id, @RequestParam OrderStatus status,
                               @RequestHeader(value = "Referer", required = false) String referer,
                               RedirectAttributes ra) {
        Order o = orderService.updateStatus(id, status);
        ra.addFlashAttribute("success", "Order " + o.getOrderNumber() + " marked " + status.getLabel().toLowerCase() + ".");
        return "redirect:" + (referer != null ? referer : "/admin/orders");
    }

    // ---------------------------------------------------------------- prescriptions

    @GetMapping("/prescriptions")
    public String prescriptions(Model model) {
        model.addAttribute("pending", prescriptionService.pending());
        model.addAttribute("reviewed", prescriptionService.recentlyReviewed());
        model.addAttribute("defaultValidUntil", LocalDate.now().plusMonths(6));
        model.addAttribute("active", "prescriptions");
        return "admin/prescriptions";
    }

    @PostMapping("/prescriptions/{id}/review")
    public String review(@PathVariable Long id, @RequestParam boolean approve,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntil,
                         RedirectAttributes ra) {
        prescriptionService.review(id, approve, note, validUntil);
        ra.addFlashAttribute("success", approve ? "Prescription approved." : "Prescription rejected.");
        return "redirect:/admin/prescriptions";
    }
}
