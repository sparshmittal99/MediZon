package com.medizon.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.domain.MedicationPlan;
import com.medizon.domain.ScheduleType;
import com.medizon.service.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The chat must say which days a dose reminder rings on, and understand the days people name. */
class PlanScheduleToolTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aWeeklyPlanIsNotReportedAsDaily() {
        MedicationPlan p = new MedicationPlan();
        p.getReminderTimes().add(LocalTime.of(13, 30));
        p.setScheduleType(ScheduleType.DAYS_OF_WEEK);
        p.getDaysOfWeek().add(DayOfWeek.TUESDAY);

        Map<String, Object> s = AssistantTools.schedule(p);
        assertThat(s.get("schedule")).isEqualTo("Every Tuesday only");
        assertThat(s.get("days")).isEqualTo(java.util.List.of("Tuesday"));
    }

    @Test
    void anEveryDayPlanSaysSo() {
        MedicationPlan p = new MedicationPlan();
        assertThat(AssistantTools.schedule(p).get("schedule")).isEqualTo("Every day");
    }

    @Test
    void understandsTheDaysPeopleName() throws Exception {
        assertThat(AssistantTools.days(json.readTree("[\"Tuesday\"]"))).containsExactly(DayOfWeek.TUESDAY);
        assertThat(AssistantTools.days(json.readTree("[\"mon\",\"THU\"]"))).containsExactly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
        assertThat(AssistantTools.days(json.readTree("\"Mon and Fri\""))).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        assertThat(AssistantTools.days(json.readTree("[\"weekends\"]"))).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(AssistantTools.days(json.readTree("[\"mangalvar\"]"))).containsExactly(DayOfWeek.TUESDAY);
        assertThat(AssistantTools.days(json.readTree("[\"daily\"]"))).hasSize(7);
        assertThatThrownBy(() -> AssistantTools.days(json.readTree("[\"someday\"]"))).isInstanceOf(BusinessException.class);
    }
}
