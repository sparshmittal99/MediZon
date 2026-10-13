package com.medizon.service;

import com.medizon.domain.ServiceablePincode;
import com.medizon.repository.ServiceablePincodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Where MediZon delivers: a list of PIN codes kept by admins. */
@Service
@RequiredArgsConstructor
public class DeliveryAreaService {

    private static final Pattern PIN = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern PIN_IN_TEXT = Pattern.compile("(?<![0-9])[1-9][0-9]{5}(?![0-9])");

    private final ServiceablePincodeRepository pincodes;

    /** "400 050" or " 400050 " become "400050"; anything that isn't a 6 digit PIN code becomes null. */
    public static String normalize(String pincode) {
        if (pincode == null) return null;
        String p = pincode.replaceAll("\\s+", "");
        return PIN.matcher(p).matches() ? p : null;
    }

    public boolean isServiceable(String pincode) {
        String p = normalize(pincode);
        return p != null && pincodes.existsByPincodeAndActiveTrue(p);
    }

    public Optional<ServiceablePincode> find(String pincode) {
        String p = normalize(pincode);
        return p == null ? Optional.empty() : pincodes.findByPincode(p);
    }

    /** Throws when an order can't be delivered to this PIN code. */
    public void requireServiceable(String pincode) {
        String p = normalize(pincode);
        if (p == null) throw new BusinessException("Add a valid 6 digit PIN code to your delivery address.");
        if (!pincodes.existsByPincodeAndActiveTrue(p)) {
            throw new BusinessException("Sorry, we don't deliver to PIN code " + p + " yet.");
        }
    }

    // ------------------------------------------------------------------ admin

    public List<ServiceablePincode> all() { return pincodes.findAllByOrderByPincodeAsc(); }

    public long activeCount() { return pincodes.countByActiveTrue(); }

    /**
     * Adds PIN codes typed one by one or pasted as a list ("400050, 400051 400052"). Area, city and state apply to
     * each; PIN codes already listed are updated and switched back on. Returns how many were added or updated.
     */
    @Transactional
    public int add(String text, String area, String city, String state) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = PIN_IN_TEXT.matcher(text);
            while (m.find()) found.add(m.group());
        }
        if (found.isEmpty()) throw new BusinessException("Enter at least one 6 digit PIN code.");
        List<ServiceablePincode> saved = new ArrayList<>();
        for (String p : found) {
            ServiceablePincode s = pincodes.findByPincode(p).orElseGet(() -> {
                ServiceablePincode n = new ServiceablePincode();
                n.setPincode(p);
                return n;
            });
            if (blankToNull(area) != null) s.setArea(area.trim());
            if (blankToNull(city) != null) s.setCity(city.trim());
            if (blankToNull(state) != null) s.setState(state.trim());
            s.setActive(true);
            saved.add(s);
        }
        pincodes.saveAll(saved);
        return saved.size();
    }

    @Transactional
    public ServiceablePincode setActive(Long id, boolean active) {
        ServiceablePincode s = get(id);
        s.setActive(active);
        return s;
    }

    @Transactional
    public ServiceablePincode delete(Long id) {
        ServiceablePincode s = get(id);
        pincodes.delete(s);
        return s;
    }

    private ServiceablePincode get(Long id) {
        return pincodes.findById(id).orElseThrow(() -> new BusinessException("That PIN code isn't listed."));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
