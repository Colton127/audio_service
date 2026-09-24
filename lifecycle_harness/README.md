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
foreground-service start here. The last five tests simulate refused and failed starts by replacing
`AudioService.foregroundPromoter`, the seam around `startForegroundService()` and
`startForeground()`; real refusals are covered by ReliefMix's host runner (`system_test/android`,
`service` category). The harness handler counts errors that reach `AudioService.asyncError` in its
media item's extras (`asyncErrorCount`, `lastAsyncError`), which the tests read through a
`MediaController`.

| Method | Checks |
|---|---|
| `commandsSentBeforeReplacementEngineConfiguresAreDelivered` | After the first engine is disposed, a replacement engine is created and two handler commands are sent in the same main-thread task, before its Dart side can configure. Both are delivered and the click starts playback |
| `mediaButtonInsideDisposalWindowNeverLeavesForegroundStartPending` | The playing service is stopped from outside, then a play-pause `MEDIA_BUTTON` broadcast reaches the app's `MediaButtonReceiver` inside the disposal delay (as `MediaSessionService` sends it when no session is left; `cmd media_session dispatch` would target whichever app Android last recorded). No `AudioService` stays `fgRequired` (started with `startForegroundService()` but not yet foreground) for more than 5 s, and the press leaves a paused `AudioService` |
| `serviceRecreatedWhilePlayingReturnsToForeground` | A playing service is stopped from outside and the Activity returns inside the disposal delay. The recreated service, which replays `playing`, is started in the foreground within 5 s |
| `refusedForegroundStartLeavesNothingHalfEstablished` | API 31+. `startForeground()` throws `ForegroundServiceStartNotAllowedException` after `startForegroundService()` went through. The service is left not in the playing state, without its wake lock or a foreground service, with its media session still active; Dart gets one `FOREGROUND_START_REFUSED` error |
| `liveUpdatesWhilePlayingDoNotRetryARefusedStart` | API 31+. After a refusal, 20 live updates (seeks, still playing) make no further attempt, and Dart is still told only once |
| `activityResumeRetriesARefusedStartOnce` | API 31+. After a refusal, pausing and resuming the Activity makes exactly one more attempt, which puts the service in the foreground with its wake lock and reports nothing to Dart |
| `foregroundStartFailureReachesDartAndIsNotRetried` | `startForeground()` throws a plain `IllegalStateException`, as for a missing or invalid foreground service type. Dart gets the error; neither 20 live updates nor an Activity resume retry it; a pause followed by a new play tries once more |
| `failedRetryOnActivityResumeIsReportedNotThrown` | API 31+. After a refusal, the retry from the Activity's resume fails with a plain `IllegalStateException`. It is reported, not thrown into the lifecycle callback: the process survives, the service keeps playing without the playing state, Dart gets it through `AudioService.asyncError` (code `AudioService.retryForegroundIfPlaying`), and the next resume does not retry. A pause followed by a new play tries once more and reports the failure to Dart |
| `idleLeavesForegroundOnceAndDestructionDoesNotStopAgain` | RELIEFMIX-3R5. Stopping the playing handler (idle) makes one `stopForeground(STOP_FOREGROUND_REMOVE)` and removes the notification; destroying the service afterwards makes no further `stopForeground()` call |
| `pauseLeavesForegroundKeepingNotificationAndDestructionDoesNotStopAgain` | Pausing makes one `stopForeground(STOP_FOREGROUND_LEGACY)`, which keeps the notification while the service lives; destroying the service makes no further call, and the notification goes with it |
| `destructionInForegroundLeavesTheForegroundToTheSystem` | A service destroyed while in the foreground makes no `stopForeground()` call; it ends up out of the foreground and its notification is removed |
| `recreatedServiceTracksItsOwnForegroundState` | An instance destroyed in the foreground makes no call; its replacement, restored to playing and back in the foreground, leaves it with exactly one `STOP_FOREGROUND_REMOVE` of its own |

The stop tests grant `POST_NOTIFICATIONS` on API 33+ (declared in the harness manifest) so that
they can check the notification, and record `stopForeground()` calls through the same seam.
`onDestroy()` makes no `stopForeground()` call: before calling it, the system has already taken the
service out of the foreground and cancelled a notification still attached to it
(`ActiveServices.bringDownServiceLocked`).

### `PlatformErrorTest`

The plugin's unified error handling, `AudioServiceErrors.report()`: an error the plugin catches
instead of letting it crash the app is logged to logcat and, while an AudioHandler's engine is
attached, delivered to Dart's `AudioService.asyncError` as a `PlatformException` whose code names
the method that caught it. The harness handler answers the children of `slow` after five seconds and
throws for the children of `failing`.

| Method | Checks |
|---|---|
| `reportedErrorsReachAsyncErrorFromAnyThread` | Errors reported on the main thread and on the instrumentation thread both reach `AudioService.asyncError`, with their `where` as the code |
| `reportedErrorWithoutAnEngineIsOnlyLogged` | With no engine, reporting from either thread does not fail, and the error is not delivered to an engine created afterwards |
| `failingBrowseRequestIsAnsweredWithAnError` | The handler's `getChildren` throws. The subscribing `MediaBrowser` gets `onError` and the process survives. This used to be answered with `Result.sendError()`, which `MediaBrowserServiceCompat` only supports for custom actions. The `UnsupportedOperationException` did not crash the app (Flutter's `MethodChannel` logs an exception thrown by a reply handler) but left the request unanswered |
| `browseAnswerAfterServiceDestroyedIsReportedNotThrown` | `AudioService` is destroyed while the handler is still answering a `getChildren` request (the engine is kept alive). Building the answer needs the service; the `NullPointerException` used to be logged by `MethodChannel` only, leaving the request unanswered and Dart uninformed. It is logged as `AudioHandlerInterface.getChildren failed` and delivered to Dart (`handler_result method=onPlatformError outcome=success`) |

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

### `lifecycle-repro` @ `ea4ee28` + test fixes: first device run of all 22 tests

Device: `emulator-5554`, AVD `Phone_Screenshots`, Android 16 / API 36, Flutter 3.47.5. The
foreground stop tests have not run on API 29 or 31–33; only API 35/36 images were available, and the
API 35 AVD did not boot.

`22 tests, 0 failed`, after one test fix. On the first run 20 passed;
`liveUpdatesWhilePlayingDoNotRetryARefusedStart` and `foregroundStartFailureReachesDartAndIsNotRetried`
failed with `The live updates were not all applied`. They waited for `PlaybackState.getPosition()` to
equal the last seek (20000 ms), but `MediaSessionRecord` extrapolates the position of a playing state
for controllers (seen: `position=49973` after the 30 s wait). The harness `seek()` now also publishes
the buffered position, which is not extrapolated, and the tests wait for that. The other assumptions
held: `POST_NOTIFICATIONS` granted through `UiAutomation` lets `getActiveNotifications()` list id 1124;
`moveToState(STARTED)`/`RESUMED` fires one `onActivityResumed`; relaunches land inside the 1 s window;
a null browse result reaches the client as `onError`.

Lifecycle log of the stop and refusal tests (`AudioServiceLifecycle`, abridged):

```text
started: pauseLeavesForegroundKeepingNotificationAndDestructionDoesNotStopAgain
event=foreground_started serviceGeneration=4 reason=enter_playing_state
event=foreground_stop_requested serviceGeneration=4 reason=exit_playing_state removeNotification=false
event=service_destroy_begin serviceGeneration=4 inForeground=false
started: destructionInForegroundLeavesTheForegroundToTheSystem
event=foreground_started serviceGeneration=8 reason=enter_playing_state
event=service_destroy_begin serviceGeneration=8 inForeground=true
event=service_destroyed_while_playing generation=6 serviceGeneration=8 processingState=ready
started: idleLeavesForegroundOnceAndDestructionDoesNotStopAgain
event=foreground_stop_requested serviceGeneration=9 reason=processing_state_idle removeNotification=true
event=service_destroy_begin serviceGeneration=9 inForeground=false
started: activityResumeRetriesARefusedStartOnce
event=foreground_start_refused serviceGeneration=16 error=ForegroundServiceStartNotAllowedException
event=foreground_retry serviceGeneration=16 reason=activity_resumed
event=foreground_started serviceGeneration=16 reason=enter_playing_state
```

`service_destroyed_while_playing` is also logged after an idle stop (`processingState=idle`), because
`BaseAudioHandler.stop()` publishes idle without clearing `playing`. It is diagnostic only.

Each fix was reverted locally (not committed) to check that its tests catch it:

| Revert | Tests that fail |
|---|---|
| `ea4ee28`: `onDestroy()` calls `stopForeground()` again, unconditionally as before | All four stop tests, on their recorded calls, e.g. `onDestroy() must not call stopForeground() again expected:<[REMOVE@9]> but was:<[REMOVE@9, LEGACY@9]>`, and `destructionInForegroundLeavesTheForegroundToTheSystem`: `expected:<[]> but was:<[LEGACY@8]>` |
| `cc2bf21`: playing flag and wake lock set before the foreground calls | All five refusal/failure tests, e.g. `A refused start must not leave the playing state entered`; `activityResumeRetriesARefusedStartOnce` never retries |
| `c027986`: retry from the resume callback may throw | `failedRetryOnActivityResumeIsReportedNotThrown`: `FATAL EXCEPTION: main` in `AudioServicePlugin$3.onActivityResumed`, instrumentation process crashed |
| `5f3beaf`: browse errors answered with `sendError()` | `failingBrowseRequestIsAnsweredWithAnError`: `The client was not told that loading the children failed`. No crash: `MethodChannel` logged `Failed to handle method call result` / `UnsupportedOperationException: It is not supported to send an error for failing` |
| `5f3beaf`: no guard around building a `getChildren` answer | `browseAnswerAfterServiceDestroyedIsReportedNotThrown`: `The failure to build the answer was not reported`. No crash: `MethodChannel` logged the `NullPointerException` |

So the browse fixes answer requests that were left pending; they did not fix a crash. Flutter's
`MethodChannel.IncomingResultHandler` has caught exceptions from reply handlers since 2017.

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

The five refusal and failure tests were added afterwards, together with making the foreground start
all-or-nothing, and so were the four `PlatformErrorTest` tests, with the unified error handling, and
the four foreground stop tests; see the next section for their first device run. They need the
`foregroundPromoter` seam and `AudioServiceErrors`, so they do not compile against earlier revisions.

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
