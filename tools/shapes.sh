#!/usr/bin/env bash
# The four shapes of the phone. The Fold's glass in dp, from its own screenshots: cover 411 x 960,
# main 859 x 896. The emulator can't go past 1920 px, so densities are lowered to keep the dp, and
# the display really turns (user_rotation). For each shape: the thread at rest, with the next knot,
# now and the loose end in view (checked from files/layout.json), then a knot's detail.
PKG=com.cerniera.nodo
adb shell settings put system accelerometer_rotation 0

shape() { adb shell wm density "$2"; adb shell wm size "$1"; adb shell settings put system user_rotation "$3"; sleep 3; }

check() {
  for try in 1 2 3 4 5 6; do json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null); [ -n "$json" ] && break; sleep 1; done
  python3 - "$1" "$2" "$json" <<'PY'
import sys, json
name, want = sys.argv[1], sys.argv[2]
try: d = json.loads(sys.argv[3])
except Exception: print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]; ox, oy = d["origin"]
bad, seen = [], []
if d["shape"] != want: bad.append(f"laid out as {d['shape']}, expected {want}")
for k in ("next", "now", "end", "head"):
    if k not in d["parts"]: bad.append(f"{k} not laid out"); continue
    l, t, r, b = d["parts"][k]; l -= ox; r -= ox; t -= oy; b -= oy
    if l < il or t < it or r > vw - ir or b > vh - ib: bad.append(f"{k} at {[l, t, r, b]} outside {vw}x{vh} less bars {d['insets']}")
    else: seen.append(k)
print(("::error::" if bad else "::notice::") + f"{name} ({d['shape']}): " + ("; ".join(bad) if bad else ", ".join(seen) + f" in view {vw}x{vh}"))
PY
}

open_thread() { adb shell run-as $PKG rm -f files/layout.json; adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 5; }

for s in "cover-portrait 823x1920 320 0 COVER_P" "cover-landscape 823x1920 320 1 COVER_L" "main-portrait 1503x1568 280 0 MAIN_P" "main-landscape 1503x1568 280 1 MAIN_L"; do
  set -- $s; name=$1
  shape "$2" "$3" "$4"; open_thread
  want=$([ "$4" = 0 ] && echo 0 || echo 1)
  for try in 1 2 3; do
    turned=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;print(int(json.load(sys.stdin)['rotation'] % 2))" 2>/dev/null)
    [ "$turned" = "$want" ] && break
    echo "rotation not applied (try $try), asking again"
    adb shell settings put system user_rotation 0; sleep 1; adb shell settings put system user_rotation "$4"; sleep 3; open_thread
  done
  [ "$turned" = "$want" ] || echo "::error::$name: the screen never turned" | tee -a shapes.txt
  adb exec-out screencap -p > "shot-shape-$name.png"
  check "Thread, $name" "$5" | tee -a shapes.txt
  adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es open next; sleep 4
  adb exec-out screencap -p > "shot-shape-$name-detail.png"
  adb shell am force-stop $PKG
done

adb shell settings put system user_rotation 0
adb shell wm size reset; adb shell wm density reset
sleep 3
