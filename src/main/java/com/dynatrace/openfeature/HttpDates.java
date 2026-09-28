package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Parsing and formatting of HTTP-date header values (RFC 7231 IMF-fixdate). */
final class HttpDates {

    private static final Logger log = LoggerFactory.getLogger(HttpDates.class);

    /** RFC 7231 IMF-fixdate canonical form (always GMT, two-digit day). */
    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    private HttpDates() {
    }

    /** Parses an RFC 1123 HTTP-date. Returns null if absent or unparseable. */
    static ZonedDateTime parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException e) {
            log.warn("unparseable HTTP-date '{}': {}", value, e.getMessage());
            return null;
        }
    }

    /** Parses an RFC 1123 HTTP-date to Unix epoch seconds. Returns null if absent or unparseable. */
    static Long parseEpochSeconds(String value) {
        ZonedDateTime parsed = parse(value);
        return parsed == null ? null : parsed.toEpochSecond();
    }

    /** Formats as canonical IMF-fixdate. Null-safe. */
    static String format(ZonedDateTime value) {
        return value == null ? null : IMF_FIXDATE.format(value.withZoneSameInstant(ZoneOffset.UTC));
    }
}
