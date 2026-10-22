// Minimal stand-in for androidx.annotation.ChecksSdkIntAtLeast (see third_party/androidx-test/README.md).
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface ChecksSdkIntAtLeast {
    int api() default -1;
    String codename() default "";
    int parameter() default -1;
    int lambda() default -1;
}
