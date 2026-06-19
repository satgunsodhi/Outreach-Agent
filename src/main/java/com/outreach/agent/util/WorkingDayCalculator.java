package com.outreach.agent.util;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

@Component
public class WorkingDayCalculator {

    public LocalDateTime calculateNextWorkingDay8AmIst() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime nowIst = ZonedDateTime.now(istZone);

        // B8: If it is before 8 AM on a weekday, return today at 8 AM rather than N+1 day.
        ZonedDateTime todayAt8 = nowIst.withHour(8).withMinute(0).withSecond(0).withNano(0);
        boolean isWeekday = nowIst.getDayOfWeek() != DayOfWeek.SATURDAY && nowIst.getDayOfWeek() != DayOfWeek.SUNDAY;
        if (isWeekday && nowIst.isBefore(todayAt8)) {
            return todayAt8.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }

        // Otherwise, advance to the next calendar day at 8 AM and skip weekends.
        ZonedDateTime candidate = nowIst.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }

        return candidate.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
