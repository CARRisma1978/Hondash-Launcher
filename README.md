# Hondash Launcher

Replaces the Tasker tasks **On Task** and **Off Task** (the "Hondash" profile, triggered by power) on a rooted Pixel 5.
Icon: speedometer gauge. Plain Kotlin with no libraries. The APK is about 48 KB. It needs root (Magisk).

> **Heads up:** this is a very personal, niche app. I built it to do exactly what I wanted on my
> own rooted Pixel 5 in my car, replacing my own Tasker profile, so it probably won't be useful to
> many other people as it is. You're welcome to borrow ideas or code from it, but it isn't a
> general-purpose app and I'm not planning to add features for other setups.
>
> This project isn't affiliated with or endorsed by Hondash. It only launches the Hondash app
> (`net.hondash.hondash`), which you need to install separately.

## What it does

| When | Steps (Tasker order) |
|---|---|
| USB power on (ignition on) | Bluetooth on, play the power-on sound (Leaf), Battery Saver off (and its "sticky" flag cleared), wait until Bluetooth is on, launch Hondash |
| USB power off for 10 s | Screen off, close Hondash, Bluetooth off, play the power-off sound (Chime) and let it finish, Battery Saver on |
| Power back within 10 s | Nothing happens (same as Tasker's %TRUN / %PACTIVE checks) |

The two disabled `input tap` shell actions in Off Task are left out.

## Install

1. If you installed "Adam's Pixel 5 App" 1.0, uninstall it first (this is a new package).
2. Download `HondashLauncher-1.18.apk` from the [Releases](https://github.com/CARRisma1978/hondash-launcher/releases) page on the phone and open it. Allow "Install unknown apps" if asked.
3. Open the app and tap **Grant / check root**. Allow it in the Magisk prompt.
4. Turn the main switch on.
5. In Tasker, turn the Hondash profile **off** so the two apps don't both run.
6. Use **Run On now** and **Run Off now** to test. Each step is shown in the log.

## Notes

- Android only delivers charger events to apps that are running, so a small background service
  stays running. It shows a minimised notification, which you can hide by long-pressing it
  (or turn off notifications for the app). With root, the app also adds itself to the
  battery-optimisation whitelist so the service isn't put to sleep.
- Android only sees whether power is coming in, not whether a cable is plugged in, so a cable left in
  with the ignition off counts as "off". The service starts at boot; if power is already on at that
  moment (phone switched on in the car), On runs straight away. If the phone has a PIN or pattern,
  Android only lets apps start after the first unlock, so On runs once you unlock.
- If Bluetooth hasn't come on after 30 s, Hondash is launched anyway. (Tasker would wait forever.)
- Sounds: pick any notification sound (or Silent) for power on and power off. Until you pick one,
  Leaf and Chime are used; if they can't be found, the default notification sound plays.
- Why the power-off sound used to be silent: turning Battery Saver on (end of Off) sets Android's
  "sticky" flag. Android then switches Battery Saver off while charging and back on the instant the
  power goes, and on this phone Extreme Battery Saver comes with it and pauses ("suspends") this app.
  Android mutes paused apps (the audio log shows "OpPlayAudio … muted"). On now clears the sticky flag,
  and Off plays the sound before turning Battery Saver on. Optional extra safety: add Hondash Launcher
  to Settings > Battery > Battery Saver > Extreme Battery Saver > Essential apps.
- "Which power counts" lets you limit the trigger to wired or wireless chargers. The default is any.

## Building

`gradlew assembleRelease` (Android SDK 34, JDK 17+).

The release signing key is **not** in this repository. Builds read it from a
`keystore.properties` file in the project root (ignored by git):

```properties
storeFile=app/adams-pixel5.keystore
storePassword=...
keyAlias=adams-pixel5
keyPassword=...
```

Without that file, the app is signed with the standard Android debug key instead. That build
works, but it won't install as an update over the released APK: you'd have to uninstall that
first. I keep the original key on my PC with a backup elsewhere, because if it's lost, future
builds can't update the installed app.

Code: `app/src/main/java/com/adamcarr/hondashlauncher/`
- `Routines.kt`: the On and Off steps, plus the sounds
- `PowerService.kt`: the charger watcher and the 10 s wait
- `PowerLogic.kt`: the trigger rules (unit-tested in `app/src/test`)
- `Root.kt`, `Store.kt`, `MainActivity.kt`

## Licence

MIT. See [LICENSE](LICENSE).
