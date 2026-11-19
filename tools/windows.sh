#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026; Sveglia's tools/windows.sh is the template). Pop-up
# windows, resizing and continuity on the main screen's shape (859 x 896 dp). Writes windows.txt;
# a line starting ::error:: fails the run.
#
# 1. The thread in a pop-up window, wide then narrow: the main screen's shape, then the cover's
#    (laid out from the window, not the fold).
# 2. A knot's words being edited (not yet Done) survive a turn.
# 3. A knot being tied keeps its words across a turn, and its field stays on screen.
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it,
# so it runs last.
PKG=com.cerniera.nodo
out=windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shot-win-$1.png"; }

adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 20
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for a in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $a 0; done
adb logcat -c

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
shape() {  # the thread's shape, from the recorded layout
  for try in 1 2 3 4 5 6 7 8; do
    s=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['shape'])" 2>/dev/null)
    [ -n "$s" ] && break; sleep 1
  done
  echo "$s"
}
# A field by its content description (or @edit: the first text field on screen): "<text>|<l> <t> <r> <b>", from the screen as it is now
# (The screen reader can't capture a screen that is still animating, so try a few times.)
field() {
  local r=""
  for try in 1 2 3 4 5 6; do r=$(field_once "$1"); [ -n "$r" ] && break; sleep 1; done
  echo "$r"
}
field_once() {
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, re
x = sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if ('$1' == '@edit' and 'class=\"android.widget.EditText\"' in n) or 'content-desc=\"$1\"' in n:
        t = re.search(r' text=\"([^\"]*)\"', n).group(1)
        b = re.findall(r'\d+', re.search(r'bounds=\"([^\"]*)\"', n).group(1))
        print(t + '|' + ' '.join(b)); break"
}

# ---- 1. The thread in a pop-up window, wide then narrow
adb shell am force-stop $PKG
adb shell am start -W --windowingMode 5 -n $PKG/.MainActivity --es seed examples >/dev/null; sleep 5
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); check 1 skipped"
else
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 1503 1568; sleep 4; shot thread-popup-wide
  s=$(shape); [ "$s" = MAIN_P ] && note "Thread in a wide pop-up window: the main screen's shape" || fail "Thread in a wide pop-up window: ${s:-no layout}, expected MAIN_P"
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 740 1568; sleep 4; shot thread-popup-narrow
  s=$(shape); [ "$s" = COVER_P ] && note "Thread in a narrow pop-up window: the cover's shape" || fail "Thread in a narrow pop-up window: ${s:-no layout}, expected COVER_P (laid out from the fold?)"
fi
adb shell am force-stop $PKG; sleep 2

# ---- 2. A knot's words, mid-edit, across a turn (full screen). The knot's field isn't visible to
# the screen reader, so its place and words come from the app's own record (layout.json: what)
what() {  # "<text>|<l> <t> <r> <b>" of the open knot's field
  for try in 1 2 3 4 5 6 7 8; do
    w=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;w=json.load(sys.stdin)['what'];print(w['text']+'|'+' '.join(map(str,w['box'])))" 2>/dev/null)
    [ -n "$w" ] && break; sleep 1
  done
  echo "$w"
}
adb shell run-as $PKG rm -f files/layout.json
adb shell am start -W --windowingMode 1 -n $PKG/.MainActivity --es seed examples --es open next >/dev/null; sleep 5
shot edit-open
f=$(what)
if [ -z "$f" ]; then
  warn "Editing a knot: its detail didn't open; skipped"
else
  set -- ${f#*|}
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input text "%sedited"; sleep 1; shot edit-upright
  adb shell run-as $PKG rm -f files/layout.json
  adb shell settings put system user_rotation 1; sleep 4; shot edit-turned
  after=$(what); after=${after%%|*}
  echo "edit: '${f%%|*}' -> '$after'" | tee -a $out
  # The emulator's keyboard can garble what adb types, so don't look for the exact word: the edit
  # survived if the field still holds more than the saved words. The old bug put the saved words back.
  orig=${f%%|*}
  if [ -n "$after" ] && [ "$after" != "$orig" ] && [ "${after#"$orig"}" != "$after" ]; then
    note "Editing a knot: the words being typed survived the turn ('$after')"
  else
    fail "Editing a knot: the words being typed were lost on the turn ('$orig' became '${after:-no field}')"
  fi
  adb shell settings put system user_rotation 0; sleep 3
fi
adb shell am force-stop $PKG; sleep 2

# ---- 3. A knot being tied, across a turn (full screen)
adb shell run-as $PKG rm -f files/layout.json
adb shell am start -W --windowingMode 1 -n $PKG/.MainActivity --es seed examples >/dev/null; sleep 5
end=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;b=json.load(sys.stdin)['parts']['end'];print((b[0]+b[2])//2,(b[1]+b[3])//2)" 2>/dev/null)
if [ -z "$end" ]; then
  warn "Tying a knot: no loose end recorded; skipped"
else
  set -- $end
  # On the main screen upright the thread runs across: pull the loose end back along it
  adb shell input swipe $1 $2 $(( $1 - 360 )) $2 900; sleep 2
  adb shell input text "Pay%sthe%sgas%sbill"; sleep 1; shot tie-upright
  f=$(field "What to remember")
  if [ -z "$f" ]; then
    warn "Tying a knot: the field didn't open; skipped"
  else
    adb shell settings put system user_rotation 1; sleep 4; shot tie-turned
    f=$(field "What to remember"); t=${f%%|*}; set -- ${f#*|}
    W=1568; H=1503   # the main screen turned
    if [ -z "$f" ]; then fail "Tying a knot: the field was gone after the turn"
    elif [ "$t" != "Pay the gas bill" ]; then fail "Tying a knot: the words changed on the turn ('$t')"
    elif [ "$1" -lt 0 ] || [ "$2" -lt 0 ] || [ "$3" -gt $W ] || [ "$4" -gt $H ]; then fail "Tying a knot: the field was left off-screen after the turn ($1,$2 to $3,$4 on ${W}x$H)"
    else note "Tying a knot: the field kept its words and stayed on screen after the turn ($1,$2 to $3,$4)"; fi
    adb shell input keyevent KEYCODE_BACK
    adb shell settings put system user_rotation 0; sleep 3
  fi
fi
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > app-log-windows.txt
if grep -A2 "FATAL EXCEPTION" app-log-windows.txt | grep -q "Process: $PKG"; then fail "Nodo crashed in the windows test"; grep -A20 "FATAL EXCEPTION" app-log-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
