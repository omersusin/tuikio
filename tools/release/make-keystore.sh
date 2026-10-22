#!/bin/sh
# Creates a Tuikio release signing key. Run it on a machine you trust (your own computer),
# never in CI or on a shared host, and keep the output out of Git (.gitignore already
# excludes *.p12, *.jks, *.keystore and signing.properties).
#
#   tools/release/make-keystore.sh <output-dir> [alias]
#
# Writes <output-dir>/tuikio-release.p12 (PKCS#12, RSA 4096, 30 years), a random password in
# <output-dir>/keystore-password.txt, and <output-dir>/signing.properties for the build.
# The password never appears on a command line or in shell history.
set -eu
DIR=${1:?usage: make-keystore.sh <output-dir> [alias]}
ALIAS=${2:-tuikio}
umask 077
mkdir -p "$DIR"
DIR=$(cd "$DIR" && pwd)
KS="$DIR/tuikio-release.p12"
PW="$DIR/keystore-password.txt"
if [ -e "$KS" ]; then
  echo "refusing to overwrite $KS" >&2
  exit 1
fi
# 40 random characters from the base64 alphabet (about 238 bits).
head -c 48 /dev/urandom | base64 | tr -d '\n=+/' | cut -c1-40 > "$PW"
keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass:file "$PW" \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -dname "CN=Tuikio, O=Tuikio" 2>/dev/null
cat > "$DIR/signing.properties" <<PROPS
storeFile=$KS
keyAlias=$ALIAS
storePasswordFile=$PW
PROPS
echo "Created $KS"
keytool -list -v -keystore "$KS" -storepass:file "$PW" -alias "$ALIAS" | grep -E 'SHA256:|Valid from'
cat <<NOTE

Next:
  cp "$DIR/signing.properties" signing.properties     # or export TUIKIO_KEYSTORE etc.
  ./gradlew :app:assembleReleaseApk

Back up the keystore and the password separately (e.g. an encrypted backup plus a password
manager). If the key is lost, installed copies can never be updated; if it leaks, anyone
can publish updates that Android accepts as Tuikio.
NOTE
