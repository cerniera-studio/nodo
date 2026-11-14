# Nodo

Reminders as knots on one thread, for the Galaxy Z Fold. Part of the Cerniera family, built on the
[Kit](https://github.com/cerniera-studio/kit) to the Bellezza Necessaria mandate.

The crease is now: what's done lies on one side, what's to come on the other. An overdue knot
gathers against the crease in rosso until it is done or moved.

- **Pull the loose end** to tie a knot, and type what to remember. A time in the words ("Friday 9",
  "in 20 min", "tomorrow at 3pm") moves the knot there.
- **Drag a knot** along the thread to move it, or across the crease to mark it done.
- **Stretch the thread** to spread today out or squeeze next week in.
- **Tap a knot** for Later, Weight, Repeat, Done and Untie. **Tap the name** for settings.

Every build on `main` publishes a release with `Nodo.apk`; other branches publish a pre-release.
The emulator job checks all four shapes of the phone, ties and drags knots, and waits for a real
reminder to arrive as a notification.

Signed with a fixed key in `signing/` so new builds install over old ones. Personal sideloading
only: never use it for a store release.
