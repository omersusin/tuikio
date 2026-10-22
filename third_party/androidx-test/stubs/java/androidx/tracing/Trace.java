// Minimal stand-in for androidx.tracing.Trace (see third_party/androidx-test/README.md): the
// monitor only brackets sections for systrace, so these forward to android.os.Trace.
package androidx.tracing;

public final class Trace {
    private Trace() {}

    public static void beginSection(String label) {
        android.os.Trace.beginSection(label);
    }

    public static void endSection() {
        android.os.Trace.endSection();
    }

    public static void forceEnableAppTracing() {}
}
