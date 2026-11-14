#!/usr/bin/env bash
# Run by the emulator job: an empty thread, the example thread in all four shapes, tying a knot
# from the loose end, dragging one across the crease, settings, and a real reminder coming due.
# Fails if the app crashed or the reminder never arrived.
set -x
PKG=com.cerniera.nodo
source tools/emu.sh
note() { echo "$1" | tee -a walk.txt; }

adb install -r Nodo.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow
adb logcat -c

# An empty thread: only the loose end and how to use it
adb shell am start -n $PKG/.MainActivity; sleep 6; shot empty
adb shell am force-stop $PKG

# The example thread, in the four shapes (each with its detail open)
adb shell am start -n $PKG/.MainActivity --es seed examples; sleep 5; shot home
bash tools/shapes.sh

# Back on the cover, upright, at the Fold's size: find the loose end, the next knot and now from the recorded layout
adb shell wm density 320; adb shell wm size 823x1920; sleep 3
adb shell am force-stop $PKG; adb shell run-as $PKG rm -f files/layout.json
adb shell am start -n $PKG/.MainActivity; sleep 5
pos() { adb shell run-as $PKG cat files/layout.json | python3 -c "import sys,json;b=json.load(sys.stdin)['parts']['$1'];print((b[0]+b[2])//2,(b[1]+b[3])//2)"; }
read EX EY < <(pos end); read NX NY < <(pos next); read OX OY < <(pos now)
note "loose end $EX,$EY · next knot $NX,$NY · now $OX,$OY"

# Pull the loose end up the thread: a knot forms, then type what to remember
adb shell input swipe $EX $EY $EX $((EY - 360)) 900; sleep 2; shot tie-open
adb shell input text "Friday%s9%scall%sthe%sbank"; sleep 1; shot tie-typed
adb shell input keyevent KEYCODE_ENTER; sleep 3; shot tied
adb shell run-as $PKG cat shared_prefs/nodo.xml | grep -q "Call the bank" && note "Tied: 'Call the bank' saved" || note "::error::the tied knot was not saved"

# Drag the next knot up across the crease (now): it is done
adb shell input swipe $NX $NY $NX $((OY - 140)) 900; sleep 3; shot dragged-done
adb shell run-as $PKG cat shared_prefs/nodo.xml > prefs.txt
python3 - <<'PY' | tee -a walk.txt
import re, json, html
x = open("prefs.txt").read()
m = re.search(r'name="knots">(.*?)</string>', x, re.S)
k = json.loads(html.unescape(m.group(1))) if m else []
c = [i for i in k if i["text"] == "Call back about the KYC role"]
print("Dragged across the crease: done" if c and "done" in c[0] else "::error::the dragged knot was not marked done")
PY

# Tap a knot: its detail
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open next; sleep 4; shot detail
# Settings, from the name
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open settings; sleep 4; shot settings
adb shell input swipe 540 1700 540 500 300; sleep 1; shot settings-lower
adb shell am force-stop $PKG

# A real reminder: a knot due in a minute arrives as a notification with Done and Later
# (Home, not force-stop: Android cancels a force-stopped app's alarms)
adb shell am start -n $PKG/.MainActivity --es add "in 1 min test knot from the emulator"; sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 75
adb shell cmd statusbar expand-notifications; sleep 2; shot notification
adb shell dumpsys notification --noredact > notes.txt
grep -q "Test knot from the emulator" notes.txt && note "Reminder arrived as a notification" || note "::error::the reminder did not arrive"
adb shell cmd statusbar collapse
adb shell wm size reset; adb shell wm density reset

adb logcat -d > app-log.txt
if crashed; then echo "::error::Nodo crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
grep "::error::" walk.txt shapes.txt && exit 1
echo "No crash"
