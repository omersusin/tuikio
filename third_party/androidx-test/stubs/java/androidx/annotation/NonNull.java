// Minimal stand-in for androidx.annotation.NonNull (see third_party/androidx-test/README.md).
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface NonNull {}
