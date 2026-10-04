# GPWS Auto

Plane-style landing callouts for your car. GPWS Auto reads your Google Maps navigation and treats
the distance to your destination as radio altitude, so as you arrive you hear
**"2500… 1000… 500… 100… 50, 40, 30, 20, 10… RETARD, RETARD"**, like a real aircraft landing.

<p>
  <img src="docs/approach-plate.png" width="300" alt="Approach plate look: 500 m to go, next call 400">
  <img src="docs/terminal-signs.png" width="300" alt="Terminal signs look: 225 m to go, last call MINIMUM">
</p>

Free, no ads, no account, no tracking. Just for fun.

If you enjoy it, you can [buy me a coffee ☕](https://buymeacoffee.com/prat12).

## What it does

**Landing callouts**
- 2500, 1000, 500, 400, 300, 200, 100, 50, 40, 30, 20, 10 metres before your destination
- MINIMUMS at a distance you choose (optional)
- RETARD, RETARD as you stop (A320 style, optional)

**Warnings** (each one can be switched off)

| Callout | When | How the phone knows |
|---|---|---|
| APPROACHING RUNWAY | Just before you join a highway or expressway | Maps' next-turn instruction |
| TERRAIN / DON'T SINK | Slopes over 6% | The air pressure sensor |
| BANK ANGLE | Corners over 0.4 g | The gyroscope, combined with GPS speed |
| SINK RATE / PULL UP | Hard braking | GPS speed dropping fast |
| TOO LOW TERRAIN | Hitting a speed breaker fast | The accelerometer feels the jolt |
| GLIDESLOPE | You miss a turn and Maps reroutes (or RETARD, for fun) | Maps' distance jumps up |
| TRAFFIC | Maps warns of a jam, crash, closure or roadworks ahead | Maps' own traffic alerts |
| Overspeed chime (Airbus master warning) | The first time each trip you go over a speed you set | GPS speed |
| Autopilot disconnect | You cancel navigation before arriving | Maps' notification goes away |

**Voices:** Boeing 777, Boeing 737, Airbus A320, MD-11 and DC-10, or "Surprise me" for a
different plane every trip. You can also use your own sounds (any audio or video file, trimmed
in the app) and share them with friends as a sound pack.

**In the car:** it arms itself when you connect to Android Auto or your car's Bluetooth, lowers
your music during callouts, gets louder at speed, and stays quiet during calls.

## Install

Needs Android 8 or newer and Google Maps, in km or miles. Maps can be in English or most other
languages; if GPWS Auto can't read yours, the home screen says so, and setting just Maps to English
(Settings → Apps → Maps → Language, Android 13+) works while it gets fixed.

1. Download the latest `.apk` from [Releases](https://github.com/Prat2412/gpws-auto/releases/latest).
2. **Check it before you install it** (recommended, takes a minute). Upload the `.apk` to
   [VirusTotal](https://www.virustotal.com) or any other online virus scanner: it's checked by
   dozens of antivirus engines, and you don't have to install anything. To be sure your download is
   the real file, compare its SHA-256 (VirusTotal shows it) with the one in the release notes.
3. Open the `.apk` to install it. If Android asks, allow your browser to install apps. If Google
   Play Protect says **"App blocked to protect your device"**, see [below](#if-google-play-protect-blocks-the-install).
4. Open **GPWS Auto** and follow the setup checklist:
   - **Notification access**, so it can read Maps' navigation notification. If Android says it's a
     *restricted setting*, go to Settings → Apps → GPWS Auto → ⋮ → **Allow restricted settings**,
     then try again.
   - **Location: Allow all the time.** The speed-based warnings need it, and it makes the final
     callouts land on time.
   - **Battery: Unrestricted**, so Android doesn't stop it mid-drive.
5. Start navigating in Google Maps and drive. To hear a landing without driving, use
   **Status → Simulated approach**.

**Tip:** if you also use the Google Maps voice, the two can talk over each other. Use one or the
other, or mute the Maps voice with the speaker button in Maps.

### If Google Play Protect blocks the install

On some phones, Android shows: *"App blocked to protect your device. This app can request access
to sensitive data. This can increase the risk of identity theft or financial fraud."*

**This isn't a virus warning.** In India and a growing list of other countries, Google blocks every
app installed from a browser, file manager or chat app if it can read notifications or SMS. Scam
apps use those to steal one-time passwords (OTPs), so Google blocks the whole kind of app without
looking at what any one app actually does. GPWS Auto has to read notifications, because that's the
only way to get the distance from Google Maps, so it's blocked automatically.

What GPWS Auto actually does with that access: it reads only Google Maps' (and Waze's) navigation
notifications and skips every other app's, so it never sees your messages or OTPs. Nothing is sent
anywhere; the drive log it keeps for bug reports stays on your phone unless you share it. You can
check all of that in [NavListener.kt](app/src/main/java/dev/gpws/auto/NavListener.kt).

To install it anyway (only for an app you trust and have checked, see step 2):

1. Open the **Play Store** → tap your **profile picture** → **Play Protect** → **⚙️**.
2. Turn off **Scan apps with Play Protect**.
3. Install the GPWS Auto `.apk`.
4. **Turn Scan apps with Play Protect back on.** It protects you from real malware, so don't leave it off.

On some phones it's under Settings → Security & privacy → App security → Google Play Protect. If
Play Protect asks about GPWS Auto later, tap **Keep app**. Updates install from inside the app
(Status → Updates), so normally you only do this once.

### Why not the Play Store?

GPWS Auto is shared here, free and open source, instead of on the Play Store: no store account,
fees or approvals between you and the app, and the code for every version is right here for anyone
to read, check or build. The catch is the Play Protect block above.

## Updates

Tap **Status → Updates** to check this page for a newer version. GPWS Auto downloads it and
Android asks you to confirm the install. It only checks when you tap.

If a Google Maps update ever changes its notification so GPWS Auto can't read it, the home
screen says **Can't read Maps**, with buttons to check for a fixed version or report it.

## Privacy

GPWS Auto reads Google Maps' navigation notification on your phone, and that's all it reads.
There are no ads, accounts or analytics, and nothing is sent anywhere except update checks to
GitHub, only when you tap Check.

## ⚠️ Disclaimer

GPWS Auto is for **entertainment only**. It is **not** a navigation app or a safety system, so
don't rely on it or take its callouts seriously. Keep your eyes and attention on the road, and drive
exactly as you would without it.

Set the app up **before you start driving**, or after stopping somewhere safe at the side of the
road. Never while driving.

You use GPWS Auto at your own risk. The developer is not responsible for any accident, injury,
damage, fine or loss resulting from its use.

## Built with AI

I'm not a programmer. The whole app was written by Claude (Anthropic's AI, Opus 5.5) from my
ideas, and I tested it in my car. Found a bug or have an idea? [Tell me here](https://docs.google.com/forms/d/e/1FAIpQLSeIMkfe_hZXBLIRI6WD3DneDG2GePbKvgT9Y7LeRID8Kkv5Cg/viewform):
a short form, no sign-in (or tap **Please add suggestions and bugs** on the app's home screen). On GitHub?
[Open an issue](https://github.com/Prat2412/gpws-auto/issues/new/choose) instead. Pull requests are welcome.

## Build it yourself

Install Android Studio (or a JDK 17+ and the Android SDK), then:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Credits

**Sounds** come from the [FlightGear](https://www.flightgear.org) flight simulator (GPL), trimmed
and level-matched:

| Sounds | Source |
|---|---|
| Boeing 777 numbers, TERRAIN, DON'T SINK, BANK ANGLE, SINK RATE, PULL UP, TOO LOW TERRAIN, autopilot disconnect | fgaddon `Aircraft/777/Sounds` |
| Boeing 737 numbers and minimums | fgaddon `Aircraft/737-800/Sounds/gpws` |
| Airbus A320 numbers, minimums and RETARD | fgaddon `Aircraft/A320-family/Sounds/GPWS` |
| MD-11 and DC-10 | fgaddon `Aircraft/MD-11/Sounds/CAWS`, `Aircraft/DC-10/Sounds/CAWS` |
| V1 | fgaddon `Aircraft/787-8/Sounds` |
| GLIDESLOPE | fgaddon `Aircraft/CRJ700-family/Sounds` chime + fgdata `Sounds/mk-viii` |
| TRAFFIC | fgdata `Sounds/tcas` |
| Overspeed (A320 continuous repetitive chime) | fgaddon `Aircraft/A320-family/Sounds/Cockpit` |

fgaddon: https://svn.code.sf.net/p/flightgear/fgaddon · fgdata: https://gitlab.com/flightgear/fgdata

APPROACHING RUNWAY and ON RUNWAY have no free real recording, so they were made for this app
with the [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) text-to-speech model (Apache 2.0,
voice "Emma"), then given the MD-11 voice's pitch, tone and speaker hiss.

**Fonts:** [Barlow](https://github.com/jpt/barlow) and [Overpass](https://github.com/RedHatOfficial/Overpass),
under the SIL Open Font License (texts in `app/src/main/assets/licenses`).

## License

The code is under the [GNU GPL v3](LICENSE) or later: you can use, change and share it, but
anything you build from it has to stay open source under the same license. The sounds keep their
FlightGear GPL terms.

The name "GPWS Auto" and the app icon aren't covered by the license. If you publish your own
version, please give it a different name and icon.

Not affiliated with Boeing, Airbus, McDonnell Douglas, Honeywell, Google or FlightGear.
