package dev.ftcsim.contracts.hooks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Entry points that rewritten robot bytecode calls instead of the JDK's ways of asking what time it is: the system clock
 * of {@code java.time}, {@code Date} and {@code Calendar}. They give the robot program the clock of the installed
 * hooks, so a program sees the same date on every run, and simulated time passing in step with the world, whatever the
 * real time. Only the instant is replaced; the time zone and the locale remain the machine's, as everything else about
 * the machine does.
 *
 * <p>The replacements have the signatures of the JDK members they replace, a constructor becoming a factory.
 */
public final class TimeHooks {
    private TimeHooks() {}

    private static Clock clock() {
        return new SimulatedClock(ZoneId.systemDefault());
    }

    private static Clock clock(ZoneId zone) {
        return new SimulatedClock(Objects.requireNonNull(zone, "zone"));
    }

    // java.util

    public static Date newDate() {
        return new Date(Hooks.currentTimeMillis());
    }

    public static GregorianCalendar newGregorianCalendar() {
        return atNow(new GregorianCalendar());
    }

    public static GregorianCalendar newGregorianCalendar(TimeZone zone) {
        return atNow(new GregorianCalendar(zone));
    }

    public static GregorianCalendar newGregorianCalendar(Locale locale) {
        return atNow(new GregorianCalendar(locale));
    }

    public static GregorianCalendar newGregorianCalendar(TimeZone zone, Locale locale) {
        return atNow(new GregorianCalendar(zone, locale));
    }

    public static Calendar getInstance() {
        return atNow(Calendar.getInstance());
    }

    public static Calendar getInstance(TimeZone zone) {
        return atNow(Calendar.getInstance(zone));
    }

    public static Calendar getInstance(Locale locale) {
        return atNow(Calendar.getInstance(locale));
    }

    public static Calendar getInstance(TimeZone zone, Locale locale) {
        return atNow(Calendar.getInstance(zone, locale));
    }

    private static <T extends Calendar> T atNow(T calendar) {
        calendar.setTimeInMillis(Hooks.currentTimeMillis());
        return calendar;
    }

    // java.time.Clock

    public static Clock systemUtc() {
        return new SimulatedClock(ZoneOffset.UTC);
    }

    public static Clock systemDefaultZone() {
        return clock();
    }

    public static Clock system(ZoneId zone) {
        return clock(zone);
    }

    public static Clock tickMillis(ZoneId zone) {
        return Clock.tick(clock(zone), Duration.ofMillis(1));
    }

    public static Clock tickSeconds(ZoneId zone) {
        return Clock.tick(clock(zone), Duration.ofSeconds(1));
    }

    public static Clock tickMinutes(ZoneId zone) {
        return Clock.tick(clock(zone), Duration.ofMinutes(1));
    }

    // java.time

    public static Instant instantNow() {
        return Instant.now(clock());
    }

    public static LocalDate localDateNow() {
        return LocalDate.now(clock());
    }

    public static LocalDate localDateNow(ZoneId zone) {
        return LocalDate.now(clock(zone));
    }

    public static LocalTime localTimeNow() {
        return LocalTime.now(clock());
    }

    public static LocalTime localTimeNow(ZoneId zone) {
        return LocalTime.now(clock(zone));
    }

    public static LocalDateTime localDateTimeNow() {
        return LocalDateTime.now(clock());
    }

    public static LocalDateTime localDateTimeNow(ZoneId zone) {
        return LocalDateTime.now(clock(zone));
    }

    public static ZonedDateTime zonedDateTimeNow() {
        return ZonedDateTime.now(clock());
    }

    public static ZonedDateTime zonedDateTimeNow(ZoneId zone) {
        return ZonedDateTime.now(clock(zone));
    }

    public static OffsetDateTime offsetDateTimeNow() {
        return OffsetDateTime.now(clock());
    }

    public static OffsetDateTime offsetDateTimeNow(ZoneId zone) {
        return OffsetDateTime.now(clock(zone));
    }

    public static OffsetTime offsetTimeNow() {
        return OffsetTime.now(clock());
    }

    public static OffsetTime offsetTimeNow(ZoneId zone) {
        return OffsetTime.now(clock(zone));
    }

    public static Year yearNow() {
        return Year.now(clock());
    }

    public static Year yearNow(ZoneId zone) {
        return Year.now(clock(zone));
    }

    public static YearMonth yearMonthNow() {
        return YearMonth.now(clock());
    }

    public static YearMonth yearMonthNow(ZoneId zone) {
        return YearMonth.now(clock(zone));
    }

    public static MonthDay monthDayNow() {
        return MonthDay.now(clock());
    }

    public static MonthDay monthDayNow(ZoneId zone) {
        return MonthDay.now(clock(zone));
    }

    // java.time.chrono

    public static HijrahDate hijrahDateNow() {
        return HijrahDate.now(clock());
    }

    public static HijrahDate hijrahDateNow(ZoneId zone) {
        return HijrahDate.now(clock(zone));
    }

    public static JapaneseDate japaneseDateNow() {
        return JapaneseDate.now(clock());
    }

    public static JapaneseDate japaneseDateNow(ZoneId zone) {
        return JapaneseDate.now(clock(zone));
    }

    public static MinguoDate minguoDateNow() {
        return MinguoDate.now(clock());
    }

    public static MinguoDate minguoDateNow(ZoneId zone) {
        return MinguoDate.now(clock(zone));
    }

    public static ThaiBuddhistDate thaiBuddhistDateNow() {
        return ThaiBuddhistDate.now(clock());
    }

    public static ThaiBuddhistDate thaiBuddhistDateNow(ZoneId zone) {
        return ThaiBuddhistDate.now(clock(zone));
    }
}
