package dev.ftcsim.runtime.fixture;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Every way a program asks the JDK what time it is, as a call, a constructor, a method reference and a lambda. */
public final class TimeProbe implements Callable<List<String>> {
    /** A date the program makes by extending {@code Date}, whose constructor reaches {@code Date()} through {@code super()}. */
    public static final class StampedDate extends Date {
        private static final long serialVersionUID = 1L;

        public StampedDate() {
            super();
        }
    }

    @Override
    public List<String> call() {
        List<String> seen = new ArrayList<>();
        seen.add("System.currentTimeMillis " + System.currentTimeMillis());
        seen.add("Date " + new Date().getTime());
        seen.add("Date subclass " + new StampedDate().getTime());
        Supplier<Date> reference = Date::new;
        seen.add("Date::new " + reference.get().getTime());
        seen.add("Instant " + Instant.now().toEpochMilli());
        Supplier<Instant> lambda = () -> Instant.now();
        seen.add("Instant lambda " + lambda.get().toEpochMilli());
        seen.add("LocalDate " + LocalDate.now(ZoneOffset.UTC));
        seen.add("LocalDateTime " + LocalDateTime.now(ZoneOffset.UTC));
        seen.add("ZonedDateTime " + ZonedDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli());
        seen.add("OffsetDateTime " + OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli());
        seen.add("Year " + Year.now(ZoneOffset.UTC));
        seen.add("Clock.systemUTC " + Clock.systemUTC().millis());
        seen.add("Clock.system " + Clock.system(ZoneOffset.UTC).instant().toEpochMilli());
        seen.add("Clock.tickSeconds " + Clock.tickSeconds(ZoneOffset.UTC).millis());
        seen.add("Calendar " + Calendar.getInstance().getTimeInMillis());
        seen.add("GregorianCalendar " + new GregorianCalendar().getTimeInMillis());
        seen.add("GregorianCalendar.getInstance " + GregorianCalendar.getInstance().getTimeInMillis());
        return seen;
    }
}
