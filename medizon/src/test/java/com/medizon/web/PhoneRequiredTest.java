package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.dto.PhoneForm;
import com.medizon.service.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Customers without a mobile number add one before anything else. */
class PhoneRequiredTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final PhoneRequiredInterceptor interceptor = new PhoneRequiredInterceptor(currentUser);

    private MockHttpServletRequest customer(String phone, String path) {
        User u = new User();
        u.setId(3L);
        u.setPhone(phone);
        when(currentUser.find()).thenReturn(Optional.of(u));
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.addUserRole("CUSTOMER");
        return r;
    }

    @Test
    void aCustomerWithoutANumberIsSentToAddOneAndComesBack() throws Exception {
        MockHttpServletRequest r = customer(null, "/cart");
        r.setQueryString("x=1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(r, res, null)).isFalse();
        assertThat(res.getRedirectedUrl()).isEqualTo("/account/phone?next=%2Fcart%3Fx%3D1");
    }

    @Test
    void withANumberNothingHappensAndItIsntCheckedAgain() throws Exception {
        MockHttpServletRequest r = customer("9876543210", "/cart");
        assertThat(interceptor.preHandle(r, new MockHttpServletResponse(), null)).isTrue();
        assertThat(r.getSession().getAttribute(PhoneRequiredInterceptor.OK)).isEqualTo(3L);
    }

    @Test
    void staffAreNeverAsked() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/admin");
        r.addUserRole("ADMIN");
        assertThat(interceptor.preHandle(r, new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    void itOnlyReturnsToPagesOnThisSite() {
        assertThat(PhoneRequiredInterceptor.safeNext("/orders")).isEqualTo("/orders");
        assertThat(PhoneRequiredInterceptor.safeNext("https://evil.example")).isEqualTo("/");
        assertThat(PhoneRequiredInterceptor.safeNext("//evil.example")).isEqualTo("/");
        assertThat(PhoneRequiredInterceptor.safeNext("/\\evil.example")).isEqualTo("/");
        assertThat(PhoneRequiredInterceptor.safeNext(null)).isEqualTo("/");
    }

    @Test
    void theNumberRuleIsTheOneUsedForCheckout() {
        assertThat("9876543210").matches(PhoneForm.PATTERN);
        assertThat("+91 98765 43210").matches(PhoneForm.PATTERN);
        assertThat("98765").doesNotMatch(PhoneForm.PATTERN);
        assertThat("").doesNotMatch(PhoneForm.PATTERN);
    }
}
