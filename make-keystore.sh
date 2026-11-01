#!/usr/bin/env bash
# One-time: creates a release keystore + keystore.properties. Keep BOTH files safe and private
# (back them up!) — you need the same key to install future updates over the same app.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [ -f youtubly-release.jks ]; then echo "youtubly-release.jks already exists — not overwriting."; exit 1; fi
command -v keytool >/dev/null || { echo "keytool not found. Install a JDK 17 or point JAVA_HOME at Android Studio's JBR."; exit 1; }

read -r -s -p "Choose a keystore password (min 6 chars): " PASS; echo
read -r -s -p "Repeat it: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Passwords don't match."; exit 1; }
[ ${#PASS} -ge 6 ] || { echo "Password too short."; exit 1; }

keytool -genkeypair -v -keystore youtubly-release.jks -alias youtubly \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Youtubly, O=Personal, C=AE"

cat > keystore.properties <<PROPS
storeFile=youtubly-release.jks
storePassword=$PASS
keyAlias=youtubly
keyPassword=$PASS
PROPS
chmod 600 keystore.properties youtubly-release.jks
echo "Done. Now run ./build-apk.sh"
