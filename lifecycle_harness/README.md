# AudioService lifecycle harness

A standalone Flutter app, separate from `audio_service/example`, that runs Android
instrumentation tests against the local `audio_service` checkout. It uses the plugin's own
`AudioServiceActivity`, `AudioService`, and `MediaButtonReceiver` directly. `lib/main.dart` calls
`AudioService.init` with a handler that renders no audio. The handler publishes one media item, with
artwork from a local `file://` PNG so no network is needed, and a one-item queue, then shows a
placeholder widget. Its `play()` and `pause()` only publish a playing or paused state, which is
enough to put `AudioService` in its playing foreground state.

The tests live in the plugin's Java package so they can read package-private diagnostics such as
`AudioService.instance` and `AudioServicePlugin.getFlutterEngineGeneration()`.

## Contract under test

After an `AudioService` instance is destroyed, whether or not its handler reports playing, the
shared FlutterEngine is kept for `AudioServicePlugin.ENGINE_DISPOSAL_DELAY_MS` (1000 ms):

- A client that returns within that window reuses the engine, and the recreated service gets back
  the handler's state.
- If no client returns, the engine is disposed.

Earlier revisions kept the engine indefinitely when the service was destroyed while playing. A
destroy while playing is now logged as `event=service_destroyed_while_playing`.

## Tests

### `AudioServiceLifecycleTest`

Each cycle launches `AudioServiceActivity`, waits for a new `AudioService`, closes the Activity,
waits for the service's `onDestroy` to complete, and relaunches. The process is never killed.
`@Before` disposes any engine or service left behind by another test class.

| Method | Checks |
|---|---|
| `flutterEngineSurvivesServiceRecreationWithinDisposalDelay` | 3 recreations: same PID, a distinct `AudioService` each cycle, one engine generation |
| `flutterEngineSurvivesOneHundredServiceRecreationsWithinDisposalDelay` | The same checks over 100 recreations |
| `flutterEngineIsDisposedWhenNoClientReturnsWithinDisposalDelay` | With no client, the engine is disposed no earlier than the delay; the next launch creates exactly one new generation |
| `flutterEngineIsDisposedWhenServiceIsDestroyedWhilePlaying` | The handler reports playing and the service is started in the foreground; after the Activity closes, `stopService` destroys it while still playing, as the OS does. The engine is disposed no earlier than the delay instead of being kept |

The delay is read by reflection from `AudioServicePlugin.ENGINE_DISPOSAL_DELAY_MS`. On revisions
without deferred disposal the field is absent, and the test treats the delay as 0, meaning inline
disposal. If a recreation cycle relaunches later than the delay on a build that has one, the test
reports a harness timing problem instead of a lifecycle regression.

### `ServiceRecreationTest`

This test was written on the fix branch and moved here from `audio_service/example`.

| Method | Checks |
|---|---|
| `earlyUnbindDuringServiceCreateDoesNotLoopEngines` | Binds `AudioService`, then releases the binding while `onCreate` is still booting the engine (the entry point of the ReliefMix#79 loop). Exactly one engine is created, and the generation is stable at 3 s, 6 s, and 10 s |
| `recreatedServiceReusesEngineAndRestoresState` | A service recreated inside the window reuses the engine and handler, and gets back the title, artwork, queue, playback state, and playback info. The replay causes no stop or disposal. Releasing the last binding then disposes the engine |

### `ForegroundLifecycleTest`

Checks that a playing handler ends up with a started, foreground `AudioService`, and that handler
commands reach Dart. The instrumented process counts as foreground, so Android never refuses a
foreground-service start here; refused starts are covered by ReliefMix's host runner
(`system_test/android`, `service` category).

| Method | Checks |
|---|---|
| `commandsSentBeforeReplacementEngineConfiguresAreDelivered` | After the first engine is disposed, a replacement engine is created and two handler commands are sent in the same main-thread task, before its Dart side can configure. Both are delivered and the click starts playback |
| `mediaButtonInsideDisposalWindowNeverLeavesForegroundStartPending` | The playing service is stopped from outside, then a play-pause `MEDIA_BUTTON` broadcast reaches the app's `MediaButtonReceiver` inside the disposal delay (as `MediaSessionService` sends it when no session is left; `cmd media_session dispatch` would target whichever app Android last recorded). No `AudioService` stays `fgRequired` (started with `startForegroundService()` but not yet foreground) for more than 5 s, and the press leaves a paused `AudioService` |
| `serviceRecreatedWhilePlayingReturnsToForeground` | A playing service is stopped from outside and the Activity returns inside the disposal delay. The recreated service, which replays `playing`, is started in the foreground within 5 s |

## Toolchain

The app was generated by `flutter create` on Flutter 3.47.4 and uses the declarative Flutter
Gradle plugin, AGP 9.1.0, Kotlin 2.4.0, Gradle 9.3.1, and JDK 17. The AndroidX Test dependencies
are Core 1.6.1, Runner 1.6.2, and ext-JUnit 1.2.1, plus `androidx.media` 1.7.0 for the
MediaBrowser client in `ServiceRecreationTest`. `pubspec.yaml` overrides
`audio_service_platform_interface` and `audio_service_web` with their local paths.

## Commands

From `lifecycle_harness/`, with an emulator running (the examples use `emulator-5554`):

```bash
flutter pub get
flutter build apk --debug   # also generates the ignored Gradle wrapper files
cd android
adb -s emulator-5554 logcat -c
./gradlew :app:connectedDebugAndroidTest
adb -s emulator-5554 logcat -d -s AudioServiceLifecycle:W ServiceRecreationTest:I TestRunner:I
```

To run one class or method, add
`-Pandroid.testInstrumentationRunnerArguments.class=com.ryanheise.audioservice.<Class>[#<method>]`.

The debug APK is about 170 MB. If installation fails with `INSTALL_FAILED_INSUFFICIENT_STORAGE`,
run `adb shell pm trim-caches 2G` or free space on the emulator.

## Results

### `lifecycle-repro` (on `minor` @ `4653312`)

Device: `emulator-5554`, AVD `Phone_Screenshots`, Android 16 / API 36.

Before the fixes, `minor` passed the 6 existing tests, including
`flutterEngineIsDisposedWhenServiceIsDestroyedWhilePlaying`, which had not been run before. The 3 new
tests failed:

| Test | Failure |
|---|---|
| `commandsSentBeforeReplacementEngineConfiguresAreDelivered` | `The media button sent to the replacement engine never started playback`. Log: `handler_invoke method=click flutterReady=true` before `flutter_configure`, then `handler_result method=click outcome=not_implemented`: Flutter's channel buffer discarded the click |
| `serviceRecreatedWhilePlayingReturnsToForeground` | `The recreated AudioService reports playing but was not started in the foreground` |
| `mediaButtonInsideDisposalWindowNeverLeavesForegroundStartPending` | `The media button did not leave a paused AudioService; service=null`. The receiver's binding recreated the service with the replayed `playing` state but no foreground service; its unbind destroyed it again (`service_destroyed_while_playing`), and the handler's pause reached `setState` with no service (`NullPointerException`, returned to Dart as a `PlatformException`). No crash: `MediaButtonReceiver` binds, it does not call `startForegroundService()`, because `AudioService` declares no `MEDIA_BUTTON` intent filter |

With the fixes, all 9 tests pass, also on a slow API 35 AVD (`Small_Phone_API_35`, 2 cores, 1 GB). `flutterReady` is reset when the handler's engine detaches. A
replayed playing state re-enters the playing state (`foreground_retry reason=state_replay`). A refused
foreground start clears `playingStateEntered`, and the next resume of the attached Activity retries it.

### Earlier runs

Device: `emulator-5554`, AVD `Phone_Screenshots`, Android 16 / API 36. Flutter 3.47.4.

`flutterEngineIsDisposedWhenServiceIsDestroyedWhilePlaying` was added after these runs. On `minor` @
`d7cb502`, which still keeps the engine when the service is destroyed while playing, it is expected
to fail with `FlutterEngine was kept alive after its service was destroyed while playing`.

### Fix: `claude/audio-service-ownership-reconnect-6x2z0s` passes

`5 tests, 0 failed`. During churn, each cycle's disposal is scheduled and then cancelled when the
Activity returns:

```text
event=flutter_engine_dispose_scheduled generation=1 delayMs=1000
event=service_destroy_end serviceGeneration=1
event=flutter_engine_dispose_cancelled generation=1 reason=engine_requested_by_activity
event=flutter_engine_reused generation=1 requester=activity
event=service_create_end serviceGeneration=2 engineGeneration=1
```

State replay, from `ServiceRecreationTest` output:

```text
before: title=Harness item hasArt=true queueSize=1 playbackState=0 playbackType=1
after:  title=Harness item hasArt=true queueSize=1 playbackState=0 playbackType=1
```

### Baseline: `minor` @ `02fabf8` fails as expected

`5 tests, 4 failed`:

| Test | Failure |
|---|---|
| `flutterEngineSurvivesServiceRecreationWithinDisposalDelay` | `FlutterEngine generation changed during service recreation cycle 1 ... expected:<3> but was:<4>` |
| `flutterEngineSurvivesOneHundredServiceRecreationsWithinDisposalDelay` | `... cycle 1 ... expected:<1> but was:<2>` |
| `recreatedServiceReusesEngineAndRestoresState` | `engine must survive service destruction` |
| `earlyUnbindDuringServiceCreateDoesNotLoopEngines` | `expected:<8> but was:<21>`: the generation was 7 before the bind; the loop created 14 engines within 3 s instead of 1 |
| `flutterEngineIsDisposedWhenNoClientReturnsWithinDisposalDelay` | passes; the baseline disposes inline |
