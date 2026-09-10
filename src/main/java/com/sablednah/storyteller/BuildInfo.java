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

    /** Namespaced: a bare {@code /build.properties} would collide with every
     *  other mod doing the same thing on a shared classpath. */
    private static final String RESOURCE = "/storyteller/build.properties";

    private static final String COMMIT;
    private static final String BRANCH;
    private static final String TIME;
    private static final String VERSION;

    static {
        String commit = "unknown", branch = "unknown", time = "unknown", version = "unknown";
        // ALL-OR-NOTHING, and the ordering below is what makes it so: every
        // field is read only AFTER load() has returned. Properties.load parses
        // line by line and can throw part-way -- a file whose first line is
        // valid and whose second carries a bad backslash-u escape loads
        // `commit` and then fails. Read fields as you go, or reuse a partly-filled
        // Properties from the catch, and a corrupt stamp reports a real-looking
        // commit with the rest missing, which is worse than no stamp because it
        // looks like an answer. Verified by running it, not by reading it.
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                commit = p.getProperty("commit", commit);
                branch = p.getProperty("branch", branch);
                time = p.getProperty("time", time);
                version = p.getProperty("version", version);
            }
        } catch (Exception ignored) {
            // A missing or unreadable stamp must never stop the mod loading:
            // it is diagnostic information, not a dependency.
        }
        COMMIT = commit;
        BRANCH = branch;
        TIME = time;
        VERSION = version;
    }

    public static String commit() {
        return COMMIT;
    }

    public static String branch() {
        return BRANCH;
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
        return TIME;
    }

    /** The one-line form for the startup log: {@code 2.5.0 (build a1b2c3d4 on
     *  main, 2026-09-10T09:15:00Z)}. A {@code -dirty} suffix on the commit
     *  means it was built with uncommitted changes, which is worth seeing in
     *  somebody's log before you spend an hour reproducing against a tag. */
    public static String describe() {
        return VERSION + " (build " + COMMIT + " on " + BRANCH + ", " + TIME + ")";
    }

    private BuildInfo() {}
}
