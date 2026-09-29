# SMS Forwarder

A simple Android app that forwards incoming SMS messages to a webhook URL. Built for phones with dual SIMs where the carrier doesn't send the phone number alongside the message.

You tell the app which phone number belongs to each SIM slot, and it includes that number in every forwarded message -- so your server always knows which number received the SMS.

## Download

1. Go to the [Releases](https://github.com/Switch52/sms-forwarder/releases) page
2. Download **app-debug.apk** from the latest release
3. Transfer it to your Android phone and install it (you may need to allow "Install from unknown sources" in your phone's settings)

## Setup

1. Open the app
2. Enter your **Webhook URL** -- the server address where SMS messages should be sent
3. Enter the phone number for **SIM 1** and/or **SIM 2** (use the full number with country code, e.g. `+971501234567`)
4. Tap **Start Service**
5. Grant all permissions when prompted (SMS, Phone, Notifications)

That's it. The app will now forward every incoming SMS to your webhook.

## Features

- **Dual SIM support** -- assign a phone number to each SIM slot so the server knows which number received the message
- **Runs in the background** -- keeps forwarding even when the app is closed or the phone restarts
- **Offline queue** -- if the phone loses internet, messages are saved and sent when the connection comes back (up to 100 messages)
- **OTP filter** -- optionally forward only OTP/verification messages and skip everything else
- **Heartbeat** -- optional ping every 5 minutes so you know the phone is still online
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
