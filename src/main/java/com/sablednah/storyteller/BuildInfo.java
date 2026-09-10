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
