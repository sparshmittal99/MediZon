package com.medizon.api;

import com.medizon.service.CurrentUser;
import com.medizon.service.DeviceService;
import com.medizon.service.LiveUpdates;
import com.medizon.service.NotificationPush;
import com.medizon.service.NotificationService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A browser closing its live-update connection is a normal disconnect, not a server error. */
class NotificationStreamDisconnectTest {

    @Test
    void closedTabEndsTheStreamQuietly() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq("admin@example.com"))).thenReturn(Map.of("id", 1L, "role", "ADMIN"));
        NotificationApi api = new NotificationApi(mock(NotificationService.class), mock(CurrentUser.class),
                new NotificationPush(), jdbc, mock(DeviceService.class), mock(LiveUpdates.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();

        MvcResult opened = mvc.perform(get("/api/notifications/stream").principal(() -> "admin@example.com"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // What Tomcat does when a write hits a closed connection: report the IOException to the async listeners
        MockAsyncContext async = (MockAsyncContext) opened.getRequest().getAsyncContext();
        for (AsyncListener listener : async.getListeners()) {
            listener.onError(new AsyncEvent(async, new IOException("Broken pipe")));
        }

        MvcResult finished = mvc.perform(asyncDispatch(opened)).andExpect(status().isOk()).andReturn();
        assertThat(finished.getResolvedException()).isInstanceOf(IOException.class);   // handled, not rethrown
    }
}
