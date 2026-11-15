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
# The widget at every size it can be given, from a 2x2 on the cover to the whole main screen,
# with a full thread and with a single knot. Each is drawn at its true size and shown whole.
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open widget; sleep 4; shot widget
for seed in examples one; do
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es seed $seed; sleep 3
  for size in cover-2x2:170x170 cover-4x2:380x180 cover-4x4:380x380 cover-tall:180x420 cover-full:390x760 main-4x2:420x200 main-half:400x780 main-wide:820x300 main-full:820x780; do
    adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open widget --es size ${size#*:}; sleep 3
    adb exec-out screencap -p > "shot-widget-$seed-${size%%:*}.png"
  done
done
# Every finish (Fold Widgets' ten, from the Kit) at 4x2 on the cover, over the banded ground
for fin in VETRO CRISTALLO GOCCIA SMOKE MEMBRANE ROSSO AVORIO CARBONIO CERAMICA VISCONTI PAPER; do
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open widget --es size 380x180 --es finish $fin; sleep 3
  adb exec-out screencap -p > "shot-finish-$fin.png"
done
# Goccia beside Cristallo and Vetro, on a made-up home screen over four wallpapers, each widget
# reading the slice truly behind it
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
for wall in tools/walls/*.jpg; do
  n=$(basename "$wall" .jpg); adb push "$wall" /sdcard/Download/$n.jpg >/dev/null
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open scene --es wall /sdcard/Download/$n.jpg --es finishes GOCCIA,CRISTALLO,VETRO; sleep 4
  adb exec-out screencap -p > "shot-scene-$n.png"
done
# Fold Widgets' four corners, at 4x2
for r in 0 14 24 32; do
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open widget --es size 380x180 --es finish CARBONIO --es radius $r; sleep 3
  adb exec-out screencap -p > "shot-corners-$r.png"
done
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open widget --es finish VETRO --es radius 24; sleep 2
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es seed examples; sleep 3
# Settings, from the name
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open settings; sleep 4; shot settings
adb shell input swipe 540 1700 540 500 300; sleep 1; shot settings-lower
adb shell am force-stop $PKG

# A real reminder: a knot due in a minute arrives as a notification with Done and Later
# (Home, not force-stop: Android cancels a force-stopped app's alarms)
adb shell am start -n $PKG/.MainActivity --es add "'in 1 min test knot from the emulator'"; sleep 3
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys alarm | grep -B1 -A4 "com.cerniera.nodo" | head -30 | tee -a walk.txt
sleep 75
adb shell cmd statusbar expand-notifications; sleep 2; shot notification
adb shell dumpsys notification --noredact > notes.txt
grep -q "Test knot from the emulator" notes.txt && note "Reminder arrived as a notification" || note "::error::the reminder did not arrive"
adb shell cmd statusbar collapse
adb shell wm size reset; adb shell wm density reset

# Following Fold Widgets' look: install its latest release beside Nodo, and see Nodo's settings read it
FW_URL=https://github.com/cerniera-studio/fold-widgets/releases/latest/download/FoldWidgets.apk
[ -n "$FW_TAG" ] && FW_URL=https://github.com/cerniera-studio/fold-widgets/releases/download/$FW_TAG/FoldWidgets.apk
note "Fold Widgets beside: ${FW_TAG:-latest}"
if ls fw-branch/*.apk >/dev/null 2>&1; then cp fw-branch/*.apk fw.apk; note "Fold Widgets beside: a branch build"; else curl -sfL -o fw.apk "$FW_URL"; fi
if [ -s fw.apk ] && adb install -r fw.apk >/dev/null 2>&1; then
  adb logcat -c
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open settings; sleep 4
  adb shell input swipe 540 1700 540 300 300; sleep 1; shot follow
  line=$(adb logcat -d -s Nodo:I | grep "look:" | tail -1)
  case "$line" in
    *"from Fold Widgets"*) note "Follows Fold Widgets: ${line#*look: }" ;;
    *) note "::error::Nodo did not follow Fold Widgets' look (${line#*look: })" ;;
  esac

  # Where the widget sits, from Fold Widgets' live mode: switch it on, put Nodo's widget on the home
  # screen through the launcher's own dialog, and see Nodo take its seat from what live mode saw
  FW=com.fergusonconsultancy.foldwidgets
  adb shell settings put secure enabled_accessibility_services $FW/$FW.LiveService
  adb shell settings put secure accessibility_enabled 1
  # Both may see the wallpaper image, as on the phone, so live mode can also line it up and measure its tint
  adb shell appops set --uid $FW MANAGE_EXTERNAL_STORAGE allow; adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  adb shell input keyevent HOME; sleep 2
  adb shell am start -n $PKG/.MainActivity --es open pin; sleep 3
  adb shell uiautomator dump /sdcard/pin.xml >/dev/null 2>&1; adb pull /sdcard/pin.xml pin.xml >/dev/null 2>&1
  shot pin-dialog; cp pin.xml pin-dialog.xml 2>/dev/null
  # The launcher's button: "Add to home screen", "Add" or "Add automatically", whichever it shows
  b=$(tr '>' '\n' < pin.xml | grep -i 'text="add' | grep -o 'bounds="[^"]*"' | tail -1)
  if [ -n "$b" ]; then
    read -r x1 y1 x2 y2 <<< "$(echo "$b" | grep -o '[0-9]*' | tr '\n' ' ')"; adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); sleep 3
  fi
  adb logcat -c
  # The launcher puts it on the first page with room, usually the second: go there and rest
  read -r sw sh <<< "$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr 'x' ' ')"
  adb shell input keyevent HOME; sleep 2
  adb shell input swipe $(( sw * 85 / 100 )) $(( sh / 2 )) $(( sw * 15 / 100 )) $(( sh / 2 )) 250; sleep 8
  # Once more, now live mode is surely running: back a page and forward again, then rest
  adb shell input swipe $(( sw * 15 / 100 )) $(( sh / 2 )) $(( sw * 85 / 100 )) $(( sh / 2 )) 250; sleep 2
  adb shell input swipe $(( sw * 85 / 100 )) $(( sh / 2 )) $(( sw * 15 / 100 )) $(( sh / 2 )) 250; sleep 8
  shot home-live
  adb shell uiautomator dump /sdcard/home.xml >/dev/null 2>&1; adb pull /sdcard/home.xml home.xml >/dev/null 2>&1
  grep -q 'com.cerniera.nodo:id/root' home.xml && note "Nodo's widget is on the page the launcher shows" || note "Nodo's widget is not on the page shown"
  timeout 20 adb shell "run-as $FW cat shared_prefs/fold_widgets.xml" < /dev/null 2>/dev/null | grep -E 'live_note|follower_seats' | sed 's/^ */  Fold Widgets: /' >> walk.txt
  live=$(adb shell dumpsys accessibility | grep -c LiveService)
  adb logcat -d -s FoldWidgets:I | tail -8 | sed 's/^/  /' >> walk.txt
  seat=$(adb logcat -d -s Nodo:I | grep "seats:" | tail -1)
  case "$seat" in
    *"placed by Fold Widgets"*) note "Seat from live mode: ${seat#*seats: }" ;;
    *) note "::error::Nodo's widget was not placed by live mode (pin dialog: ${b:-not found}, live mode in accessibility: $live)" ;;
  esac
  adb shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
  adb shell pm uninstall $FW >/dev/null 2>&1
fi

adb logcat -d > app-log.txt
if crashed; then echo "::error::Nodo crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
grep "::error::" walk.txt shapes.txt && exit 1
echo "No crash"
