// Minimal stand-in for androidx.annotation.RestrictTo (see third_party/androidx-test/README.md).
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface RestrictTo {
    Scope[] value();

    enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }
}
