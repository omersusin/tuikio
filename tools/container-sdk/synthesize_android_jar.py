#!/usr/bin/env python3
"""Build a compile-only android.jar when the official SDK platform cannot be downloaded.

Container-only bootstrap (see docs/BUILD.md). The official Android SDK (dl.google.com) is
unreachable from the sandbox Tuikio was built in, so we assemble an equivalent *compile-time*
classpath from two trustworthy, publicly published sources:

  * org.robolectric:android-all:<api>-robolectric-<build>  (AOSP framework classes, Maven Central)
  * the local JDK's java.base/java.logging/java.sql/java.xml modules (core java.* API surface)

The result is never shipped: it is only used as a bootclasspath for kotlinc/javac and as a
library jar for ProGuard/dx. Developers with a normal SDK should use the official android.jar.

Usage: synthesize_android_jar.py <android-all.jar> <jdk-classes-dir> <out android.jar>
"""
import os
import sys
import zipfile

ANDROID_PREFIXES = ("android/", "com/android/", "dalvik/", "org/json/", "org/xmlpull/",
                    "org/apache/http/", "libcore/")
JDK_EXCLUDE_PREFIXES = ("jdk/", "sun/", "com/sun/")


def main(android_all, jdk_dir, out):
    seen = set()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        with zipfile.ZipFile(android_all) as src:
            for info in src.infolist():
                n = info.filename
                if n.endswith(".class") and n.startswith(ANDROID_PREFIXES) and n not in seen:
                    seen.add(n)
                    dst.writestr(zipfile.ZipInfo(n, date_time=(2008, 1, 1, 0, 0, 0)), src.read(n))
        for module in ("java.base", "java.logging", "java.sql", "java.xml"):
            root = os.path.join(jdk_dir, module)
            for dirpath, _, files in os.walk(root):
                for f in files:
                    if not f.endswith(".class") or f == "module-info.class":
                        continue
                    full = os.path.join(dirpath, f)
                    rel = os.path.relpath(full, root).replace(os.sep, "/")
                    if rel.startswith(JDK_EXCLUDE_PREFIXES) or rel in seen:
                        continue
                    seen.add(rel)
                    with open(full, "rb") as fh:
                        dst.writestr(zipfile.ZipInfo(rel, date_time=(2008, 1, 1, 0, 0, 0)), fh.read())
    print(f"wrote {out}: {len(seen)} classes")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
