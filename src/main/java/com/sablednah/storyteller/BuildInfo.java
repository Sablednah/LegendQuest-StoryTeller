package com.sablednah.storyteller;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is.
 *
 * <p>A version number answers "which release". During development that is a
 * different question from "which bytes", and the gap between them cost three
 * separate sessions time in a single day: a jar rebuilt under an unchanged
 * version number broke an already-shipped consumer, a dependency range admitted
 * a jar it could not run against, and an instance nobody could tell was stale
 * from the filename alone.</p>
 *
 * <p><b>The log line is the half that matters.</b> A stamp inside the jar says
 * what is on disk; the startup line says what actually <em>ran</em>, which is
 * the question a bug report needs answered and the one nobody could answer
 * afterwards.</p>
 *
 * <p>Read from a generated properties file rather than the manifest, because
 * this has to work in a dev run too, where the mod is loaded from a classes
 * directory and there is no jar to carry a manifest. The manifest carries the
 * same values for anything inspecting the jar without loading it.</p>
 */
public final class BuildInfo {

    /**
     * Namespaced: a bare {@code /build.properties} would collide with every
     * other mod doing the same thing on a shared classpath.
     *
     * <p><b>Keep this a literal.</b> Being a compile-time constant is what lets
     * javac inline it, so this class ends up with no reference to any mod class
     * at all and can be compiled and run standalone to exercise the failure
     * paths. Build the path from a field, a method call or config and the
     * dependency comes back silently — no compile error, the standalone test
     * just stops working. ZombieMod found that one.</p>
     */
    private static final String RESOURCE = "/storyteller/build.properties";

    /** One stamp, read as a unit. A record so a caller cannot be handed a
     *  half-filled one. */
    public record Stamp(String commit, String branch, String time, String version) {
        static final Stamp UNKNOWN = new Stamp("unknown", "unknown", "unknown", "unknown");
    }

    private static final Stamp STAMP = read();

    /**
     * Parse a stamp from a stream, or {@link Stamp#UNKNOWN} if it cannot be.
     *
     * <p><b>Public and taking a stream deliberately</b>, so the failure paths
     * can be driven directly by a test rather than through classpath games —
     * which is how four of us ended up verifying this awkwardly, by building
     * throwaway class directories to control what was on the path. Copied from
     * Chronicler, which got there first.</p>
     *
     * <p><b>ALL-OR-NOTHING, and that is the whole point of returning a record
     * built after {@code load} returns.</b> {@code Properties.load} parses line
     * by line and throws part-way on a bad escape — <em>having already
     * populated the earlier keys</em>. CityWorld confirmed that by printing
     * {@code stringPropertyNames()} from the catch and finding
     * {@code [commit, branch, version]} sitting there fully formed. So the
     * parser will hand you a half-stamp; only the throw stops you using it, and
     * only building the record afterwards stops the throw being ignored into a
     * lie. A stamp reporting a real commit with everything else missing looks
     * like an answer and is not.</p>
     *
     * <p>The catch is {@code Exception}, not {@code IOException}, and that is
     * load-bearing: {@code Properties.load} throws
     * {@code IllegalArgumentException} on a bad unicode escape. Narrowing it
     * would compile, read correctly, pass review, and take the mod down at
     * class-init as an {@code ExceptionInInitializerError} — failing to load
     * over a diagnostic. Found by Standards, testing a fallback they had
     * documented and never run.</p>
     */
    public static Stamp parse(InputStream in) {
        if (in == null) return Stamp.UNKNOWN;
        try {
            Properties p = new Properties();
            p.load(in);
            return new Stamp(p.getProperty("commit", "unknown"), p.getProperty("branch", "unknown"),
                    p.getProperty("time", "unknown"), p.getProperty("version", "unknown"));
        } catch (Exception malformed) {
            return Stamp.UNKNOWN;
        }
    }

    private static Stamp read() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return parse(in);
        } catch (Exception unreadable) {
            return Stamp.UNKNOWN;
        }
    }

    public static String commit() {
        return STAMP.commit();
    }

    public static String branch() {
        return STAMP.branch();
    }

    /**
     * <b>The commit's timestamp, not the build's.</b> This is the moment the
     * code was committed, and a jar built weeks later carries the same value.
     *
     * <p>Deliberate: a wall-clock stamp changes on every Gradle invocation, so
     * nothing downstream is ever up to date — measured at 25-31s for a no-op
     * build against 8s. It also makes the build reproducible, which a wall
     * clock actively prevents.</p>
     *
     * <p>Nothing is lost by it. The commit answers "which bytes"; the jar's own
     * mtime answers "when was this written"; and on a {@code -dirty} build the
     * commit's time is honest precisely because {@code -dirty} has already said
     * the bytes are not the commit's. Documented here as well as in
     * {@code build.gradle} because this accessor is where somebody reads
     * "time", assumes build time, and files a stale-timestamp bug.</p>
     */
    public static String time() {
        return STAMP.time();
    }

    /** The one-line form for the startup log: {@code 2.5.0 (build a1b2c3d4 on
     *  main, 2026-09-10T09:15:00Z)}. A {@code -dirty} suffix on the commit
     *  means it was built with uncommitted changes, which is worth seeing in
     *  somebody's log before you spend an hour reproducing against a tag. */
    public static String describe() {
        return STAMP.version() + " (build " + STAMP.commit()
                + " on " + STAMP.branch() + ", " + STAMP.time() + ")";
    }

    private BuildInfo() {}
}
