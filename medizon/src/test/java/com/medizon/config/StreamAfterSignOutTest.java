package com.medizon.config;

import com.medizon.api.NotificationApi;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeviceService;
import com.medizon.service.LiveUpdates;
import com.medizon.service.NotificationPush;
import com.medizon.service.NotificationService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A live-update stream whose sign-in has ended (signed out, or ended from another device) is closed once its browser
 * has gone, without a server error: the async dispatch that closes it isn't refused for the missing sign-in.
 */
@SpringJUnitWebConfig(StreamAfterSignOutTest.Config.class)
class StreamAfterSignOutTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {

        @Bean
        NotificationApi notificationApi() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForMap(anyString(), eq("admin@example.com"))).thenReturn(Map.of("id", 1L, "role", "ADMIN"));
            return new NotificationApi(mock(NotificationService.class), mock(CurrentUser.class), new NotificationPush(),
                    jdbc, mock(DeviceService.class), mock(LiveUpdates.class));
        }

        /** The production rule for async dispatches, then: everything else needs a signed-in user. */
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            http.addFilterBefore(new SignedInWhileOpening(), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> SecurityConfig.continueStartedRequests(auth).anyRequest().authenticated());
            return http.build();
        }
    }

    /**
     * Signs the stream's request in when it is opened only; nothing is kept, so by the async dispatch the sign-in is
     * gone, as it is in production once the session has ended.
     */
    static class SignedInWhileOpening extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws jakarta.servlet.ServletException, IOException {
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "admin@example.com", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            }
            chain.doFilter(request, response);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Test
    void aGoneBrowsersStreamClosesQuietlyAfterItsSignInEnded() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        MvcResult opened = mvc.perform(get("/api/notifications/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(opened.getResponse().isCommitted()).isTrue();   // "connected" already sent, as in production

        // The browser has gone: Tomcat reports the failed write, which dispatches the request again
        MockAsyncContext async = (MockAsyncContext) opened.getRequest().getAsyncContext();
        for (AsyncListener listener : async.getListeners()) {
            listener.onError(new AsyncEvent(async, new IOException("Broken pipe")));
        }
        SecurityContextHolder.clearContext();

        mvc.perform(asyncDispatch(opened)).andExpect(status().isOk());
    }
}
