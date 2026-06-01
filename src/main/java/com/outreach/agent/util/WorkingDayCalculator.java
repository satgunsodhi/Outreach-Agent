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
        
        ZonedDateTime nextDay = nowIst.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        
        while (nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextDay = nextDay.plusDays(1);
        }
        
        return nextDay.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
