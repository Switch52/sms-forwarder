# SMS Forwarder

A simple Android app that forwards incoming SMS messages to a webhook URL. Built for phones with dual SIMs where the carrier doesn't send the phone number alongside the message.

You tell the app which phone number belongs to each SIM slot, and it includes that number in every forwarded message -- so your server always knows which number received the SMS.

## Download

**[Download the latest APK](https://github.com/Switch52/sms-forwarder/releases/latest/download/app-release.apk)** -- open this link on your Android phone to download the app directly.

**Before installing**, you may need to do two things:

1. **Allow "Install from unknown sources"** -- when prompted, tap Settings and enable it, then go back and tap Install.
2. **Temporarily disable Play Protect** -- Google blocks sideloaded apps that use SMS permissions. To install:
   - Open the **Google Play Store** app
   - Tap your **profile icon** (top right) → **Play Protect** → **gear icon** (top right)
   - Turn off **Scan apps with Play Protect**
   - Install the APK
   - Turn Play Protect back on after installing

## Setup

1. Open the app
2. Enter your **Webhook URL** -- the server address where SMS messages should be sent
3. Enter the phone number for **SIM 1** and/or **SIM 2** (use the full number with country code, e.g. `+971501234567`)
4. Tap **Start Service**
5. Grant all permissions when prompted (SMS, Phone, Notifications)
6. **Disable battery optimization** -- see the section below

That's it. The app will now forward every incoming SMS to your webhook.

## IMPORTANT: Battery / Auto-Start Settings

Android phone manufacturers aggressively kill background apps. You **must** change these settings or the app **will stop working** after a few hours.

### Realme / Oppo / OnePlus (ColorOS / RealmeUI)
Settings → Battery → App Launch Management → SMS Forwarder → switch to **Manual** → enable **Auto Launch**, **Run in Background**, **Keep Alive**

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
Settings → Apps → Manage Apps → SMS Forwarder → **Autostart ON**. Also: Security → Battery Saver → SMS Forwarder → **No restrictions**

### Samsung (One UI)
Settings → Battery → Background usage limits → **Never sleeping apps** → add SMS Forwarder

### Infinix / Tecno / itel
Phone Master → App Management → Auto-start management → enable SMS Forwarder

### Huawei / Honor (EMUI)
Settings → Battery → App launch → SMS Forwarder → switch to **Manual** → enable **Auto-launch**, **Secondary launch**, **Run in background**

### All phones
Also lock the app in recent apps: open the app, tap the recents button (square), swipe down on SMS Forwarder or tap the lock icon to prevent it from being cleared.

## Features

- **Dual SIM support** -- assign a phone number to each SIM slot so the server knows which number received the message
- **Runs in the background** -- keeps forwarding even when the app is closed or the phone restarts
- **Survives app kills** -- SMS forwarding happens directly in the broadcast receiver, independent of the background service
- **Offline queue** -- if the phone loses internet, messages are saved and sent when the connection comes back (up to 100 messages)
- **Webhook auth** -- optional username/password for webhook authentication
- **Works with any phone number format** -- international, local, with or without `+`

## Webhook format

Each forwarded SMS is sent as a JSON POST request:

```json
{
  "deviceId": "abc123",
  "event": "sms:received",
  "payload": {
    "message": "Your verification code is 1234",
    "sender": "+971500000000",
    "recipient": "+971501234567",
    "simNumber": 1,
    "receivedAt": "2026-01-15T10:30:00Z"
  }
}
```

## Viewing logs

Logs are shown at the bottom of the app under "Recent Messages".

To pull the full log file via USB:

```
adb pull /sdcard/Android/data/com.fastjourney.smsforwarder/files/logs/sms_forwarder.log
```

## Remote setup via ADB

You can configure and start the app from a computer without touching the phone screen:

```
adb shell am start -n com.fastjourney.smsforwarder/.MainActivity \
  --es webhook_url "https://your-server.com/webhook" \
  --es sim1 "+971501234567" \
  --es sim2 "+971509876543" \
  --ez start true
```

## Building from source

Requires JDK 17 and Android SDK.

```
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
