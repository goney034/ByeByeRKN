#!/bin/sh
# Builds libmasque.so, the MASQUE core (usque/androidcore), into the app's jniLibs. Needs Go 1.26+.
# The result is an executable, not a library: Android only installs files named lib*.so from an
# APK next to the app with execute permission, and the app starts it as a child process.
set -eu
cd "$(dirname "$0")/usque"
out=../../app/src/main/jniLibs

build() {
    GOOS=$1 GOARCH=$2 GOARM=7 CGO_ENABLED=0 \
        go build -trimpath -buildvcs=false -ldflags='-s -w -buildid=' -o "$out/$3/libmasque.so" ./androidcore
    echo "$3: $(wc -c < "$out/$3/libmasque.so") bytes"
}

build android arm64 arm64-v8a
# Go links android/arm only through cgo; a static linux/arm binary runs on Android just as well.
build linux arm armeabi-v7a
