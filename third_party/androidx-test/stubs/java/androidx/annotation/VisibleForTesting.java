// Minimal stand-in for androidx.annotation.VisibleForTesting (see third_party/androidx-test/README.md).
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface VisibleForTesting {
    int PRIVATE = 2;
    int PACKAGE_PRIVATE = 3;
    int PROTECTED = 4;
    int NONE = 5;

    int otherwise() default PRIVATE;
}
