#!/bin/sh
# Stand up Xvfb + Openbox and audit the real compiled X11Client's wire protocol.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
D=${D:-96}
KP1=${KP1:-/tmp/kp1}
ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
KOTLINC_HOME=${KOTLINC_HOME:-/opt/kt/kotlinc}
[ -f "$KP1/com/emux/x11/X11Client.class" ] || { echo "run compile_all.sh first"; exit 2; }

rm -f /tmp/.X$D-lock; mkdir -p /tmp/.X11-unix
Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
XV=$!
i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$D-lock ] && break; i=$((i+1)); sleep 0.1; done
sleep 0.8
DISPLAY=127.0.0.1:$D openbox >/dev/null 2>&1 &
sleep 2

rm -rf /tmp/kpaudit; mkdir -p /tmp/kpaudit
"$KOTLINC_HOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$KP1" \
  "$HERE/ProtocolAudit.kt" -d /tmp/kpaudit 2>&1 | head -10
java -cp "/tmp/kpaudit:$KP1:$ANDROID_JAR:$KOTLINC_HOME/lib/kotlin-stdlib.jar" \
  ProtocolAuditKt $D
RC=$?
echo
echo "--- ground truth from xprop ---"
DISPLAY=127.0.0.1:$D xprop -root _NET_SUPPORTING_WM_CHECK _NET_WM_NAME 2>&1 | head -3
pkill -x openbox 2>/dev/null; kill $XV 2>/dev/null; rm -f /tmp/.X$D-lock
exit $RC
