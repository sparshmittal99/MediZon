package com.medizon.service;

import com.medizon.domain.Address;
import com.medizon.domain.AddressType;
import com.medizon.domain.User;
import com.medizon.dto.AddressForm;
import com.medizon.repository.AddressRepository;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * A customer's saved delivery addresses. The default one is also copied onto the account (address, city, state,
 * PIN code), which automatic refills, invoices and the assistant read.
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    public static final int MAX_ADDRESSES = 15;

    private final AddressRepository addresses;
    private final UserRepository users;
    private final MedicationPlanRepository plans;

    /** Default first. A customer who only has the old single profile address gets it as their first saved one. */
    @Transactional
    public List<Address> list(User user) {
        if (addresses.countByUser(user) == 0 && user.hasAddress()) importProfileAddress(user);
        return addresses.findByUserOrderByDefaultAddressDescCreatedAtDesc(user);
    }

    public Address get(Long id, User user) {
        return addresses.findByIdAndUser(id, user).orElseThrow(() -> new BusinessException("That address isn't saved on your account."));
    }

    public Optional<Address> find(Long id, User user) {
        return id == null ? Optional.empty() : addresses.findByIdAndUser(id, user);
    }

    @Transactional
    public Optional<Address> defaultFor(User user) {
        list(user);
        return addresses.findFirstByUserAndDefaultAddressTrue(user);
    }

    @Transactional
    public Address save(User user, AddressForm f) {
        Address a;
        if (f.getId() == null) {
            if (addresses.countByUser(user) >= MAX_ADDRESSES) {
                throw new BusinessException("You can save up to " + MAX_ADDRESSES + " addresses. Remove one you no longer use.");
            }
            a = new Address();
            a.setUser(user);
        } else {
            a = get(f.getId(), user);
        }
        a.setType(f.getType());
        a.setCustomLabel(f.getType() == AddressType.OTHER ? trim(f.getCustomLabel()) : null);
        a.setForSomeoneElse(f.isForSomeoneElse());
        a.setReceiverName(f.getReceiverName().trim());
        a.setReceiverPhone(f.getReceiverPhone().trim());
        a.setAddressLine(f.getAddressLine().trim());
        a.setArea(trim(f.getArea()));
        a.setLandmark(trim(f.getLandmark()));
        a.setCity(f.getCity().trim());
        a.setState(trim(f.getState()));
        a.setPincode(DeliveryAreaService.normalize(f.getPincode()));
        boolean noDefaultYet = addresses.findFirstByUserAndDefaultAddressTrue(user).isEmpty();
        a = addresses.save(a);
        if (f.isMakeDefault() || noDefaultYet) makeDefault(user, a);
        else if (a.isDefaultAddress()) copyToAccount(user, a);
        return a;
    }

    @Transactional
    public Address setDefault(Long id, User user) {
        Address a = get(id, user);
        makeDefault(user, a);
        return a;
    }

    /**
     * Removes an address; if it was the default, the newest remaining one takes over. Automatic refills that went
     * there go to the default address instead.
     */
    @Transactional
    public Address delete(Long id, User user) {
        Address a = get(id, user);
        boolean wasDefault = a.isDefaultAddress();
        plans.findByRefillAddress(a).forEach(p -> p.setRefillAddress(null));
        addresses.delete(a);
        addresses.flush();
        if (wasDefault) {
            List<Address> rest = addresses.findByUserOrderByDefaultAddressDescCreatedAtDesc(user);
            if (rest.isEmpty()) clearAccountAddress(user);
            else makeDefault(user, rest.get(0));
        }
        return a;
    }

    private void makeDefault(User user, Address chosen) {
        addresses.findByUserOrderByDefaultAddressDescCreatedAtDesc(user)
                .forEach(x -> x.setDefaultAddress(x.getId().equals(chosen.getId())));
        chosen.setDefaultAddress(true);
        copyToAccount(user, chosen);
    }

    private void copyToAccount(User user, Address a) {
        user.setAddressLine(a.getStreet());
        user.setCity(a.getCity());
        user.setState(a.getState());
        user.setPincode(a.getPincode());
        users.save(user);
    }

    private void clearAccountAddress(User user) {
        user.setAddressLine(null);
        user.setCity(null);
        user.setState(null);
        user.setPincode(null);
        users.save(user);
    }

    private void importProfileAddress(User user) {
        Address a = new Address();
        a.setUser(user);
        a.setType(AddressType.HOME);
        a.setReceiverName(user.getFullName());
        a.setReceiverPhone(user.getPhone() == null || user.getPhone().isBlank() ? "-" : user.getPhone());
        a.setAddressLine(user.getAddressLine());
        a.setCity(user.getCity());
        a.setState(user.getState());
        a.setPincode(user.getPincode());
        a.setDefaultAddress(true);
        addresses.save(a);
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
