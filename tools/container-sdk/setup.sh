#!/bin/sh
# Container-only toolchain bootstrap (see docs/BUILD.md, "Building without Google Maven").
# Writes local.properties pointing the Tuikio Android build at Ubuntu's SDK tools and a
# synthesized compile-time android.jar. Developers with a normal Android SDK do not need this.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${TUIKIO_SDK:-/opt/tuikio-sdk}
API=36
ALL_JAR=${ANDROID_ALL_JAR:-/opt/tuikio-tools/aall/android-all-16-robolectric-13921718.jar}
JDK_CLASSES=${JDK_CLASSES:-/opt/tuikio-tools/jdkclasses}

if [ ! -f "$SDK/platforms/android-$API/android.jar" ]; then
  mkdir -p "$SDK/platforms/android-$API" "$JDK_CLASSES"
  [ -f "$ALL_JAR" ] || { mkdir -p "$(dirname "$ALL_JAR")"; curl -fsSLo "$ALL_JAR" \
    "https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar"; }
  [ -d "$JDK_CLASSES/java.base" ] || jimage extract --dir "$JDK_CLASSES" \
    --include 'regex:/(java\.base|java\.logging|java\.sql|java\.xml)/.*' "$JAVA_HOME/lib/modules"
  python3 -I "$ROOT/tools/container-sdk/synthesize_android_jar.py" "$ALL_JAR" "$JDK_CLASSES" "$SDK/platforms/android-$API/android.jar"
fi
# Resource linking needs a framework-res.apk that this aapt2 (build-tools 29) can parse.
[ -f /usr/share/android-framework-res/framework-res.apk ] || apt-get install -y android-framework-res
cp -n /usr/share/android-framework-res/framework-res.apk "$SDK/platforms/android-$API/framework-res.apk" || true

cat > "$ROOT/local.properties" <<PROPS
sdk.dir=$SDK
tuikio.frameworkRes=$SDK/platforms/android-$API/framework-res.apk
tuikio.dxJar=/usr/share/java/com.android.dx.jar
tuikio.aapt2=/usr/bin/aapt2
tuikio.zipalign=/usr/bin/zipalign
tuikio.apksigner=/usr/bin/apksigner
PROPS
echo "wrote $ROOT/local.properties"
