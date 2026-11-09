package com.medizon.web;

import com.medizon.config.GoogleIdTokens;
import com.medizon.config.GoogleSignInConfig;
import com.medizon.config.SignInFlow;
import com.medizon.service.GoogleAccountService;
import com.medizon.service.Tokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Optional;

/**
 * Sign in with Google. On the website the button goes to Google and back (Spring Security's OAuth2 login,
 * SecurityConfig). Google doesn't allow its sign-in page inside an app's WebView, so the Android app shows the
 * phone's own Google account picker and posts the ID token it gets here.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GoogleSignInController {

    /** Session: the nonce the app's ID token must carry, so a token can't be replayed from elsewhere. */
    public static final String NONCE = "mz.google.nonce";

    private final ObjectProvider<ClientRegistrationRepository> google;
    private final GoogleIdTokens idTokens;
    private final GoogleAccountService googleAccounts;
    private final SignInFlow flow;

    /** The website's button: remembers the "Keep me signed in" box, then off to Google. */
    @GetMapping("/login/google")
    public String start(@RequestParam(required = false, defaultValue = "false") boolean remember, HttpSession session) {
        if (google.getIfAvailable() == null) return "redirect:/login";
        session.setAttribute(SignInFlow.GOOGLE_REMEMBER, remember);
        return "redirect:/oauth2/authorization/" + GoogleSignInConfig.REGISTRATION;
    }

    /** The app: the ID token from the phone's Google account picker. The app always stays signed in. */
    @PostMapping("/login/google/token")
    public String appToken(@RequestParam(required = false) String idToken, HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession();
        String nonce = (String) session.getAttribute(NONCE);
        session.removeAttribute(NONCE);
        Optional<GoogleIdTokens.Identity> who = idTokens.verify(idToken, nonce);
        if (who.isEmpty()) return "redirect:/login?google=failed";
        GoogleIdTokens.Identity id = who.get();
        try {
            GoogleAccountService.Result r = googleAccounts.resolve(id.subject(), id.email(), id.emailVerified(), id.name());
            flow.afterGoogle(request, response, r.user(), true, r.created());
            return null;
        } catch (GoogleAccountService.Refused e) {
            return "redirect:/login?google=" + e.reason().name().toLowerCase();
        }
    }

    /** What the sign-in and create-account pages need for the Google button. */
    public static void addTo(org.springframework.ui.Model model, HttpSession session, boolean webEnabled, GoogleIdTokens idTokens) {
        model.addAttribute("googleEnabled", webEnabled);
        if (webEnabled && idTokens.enabled()) {
            String nonce = (String) session.getAttribute(NONCE);
            if (nonce == null) {
                nonce = Tokens.random();
                session.setAttribute(NONCE, nonce);
            }
            model.addAttribute("googleClientId", idTokens.clientId());
            model.addAttribute("googleNonce", nonce);
        }
    }
}
