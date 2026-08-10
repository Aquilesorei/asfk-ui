# asfk — AFSK receiver (Android)

Listens on the mic, detects an AFSK tone burst (squelch trigger), and decodes it back to text via Goertzel filtering. Pair with the [asfk transmitter](../../PycharmProjects/asfk) running on a laptop.

## Run

Install debug build, grant mic permission on launch. It listens continuously — no buttons, decoded message shows for 3s then auto-reverts to listening.

```bash
./gradlew installDebug
```

## Key files

- `AfskProtocol` / `AfskDecoder.kt` — frame format + Goertzel bit decode, mirrors the transmitter's protocol
- `AfskReceiver.kt` — mic capture, energy-threshold trigger, pre-roll buffering, decode loop
- `AfskScreen.kt` — Compose UI (Listening / Capturing / Decoded / Error states)

## Notes

- Uses `AudioSource.VOICE_RECOGNITION`, not `MIC` — stock `MIC` runs AGC/noise-suppression that destroys a steady tone.
- Protocol (freq/baud/frame) must match the transmitter exactly.
