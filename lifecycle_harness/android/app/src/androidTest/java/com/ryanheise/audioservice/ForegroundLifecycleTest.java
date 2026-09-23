package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import io.flutter.embedding.engine.FlutterEngineCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground-service contract of AudioService across engine disposal and service recreation.
 *
 * <p>The instrumented process counts as foreground, so Android never refuses a foreground-service
 * start here. These tests cover what the plugin itself does: whether a playing handler ends up with
 * a started, foreground AudioService, and whether handler commands reach Dart. Refused and failed
 * starts are simulated by replacing {@link AudioService#foregroundPromoter}.</p>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ForegroundLifecycleTest {
    private static final long TIMEOUT_MS = 30_000;
    /** Well below Android's startForeground() deadline (10 s on API 31+). */
    private static final long FOREGROUND_DEADLINE_MS = 5_000;
    /** Live state updates sent while the handler keeps playing. */
    private static final int LIVE_UPDATES = 20;
    private static final String SIMULATED_FAILURE = "simulated invalid foreground service type";

    private final Context context = ApplicationProvider.getApplicationContext();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityScenario<AudioServiceActivity> scenario;

    @Before
    public void startWithoutEngineOrService() throws Exception {
        runOnMain(() -> {
            AudioServicePlugin.disposeFlutterEngine();
            return null;
        });
        await("no cached engine or service", () -> cachedEngine() == null && AudioService.instance == null, TIMEOUT_MS);
    }

    @After
    public void cleanUp() throws Exception {
        AudioService.foregroundPromoter = AudioService.FRAMEWORK_FOREGROUND_PROMOTER;
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
        context.stopService(new Intent(context, AudioService.class));
        // An engine created without an Activity keeps the service bound through its own
        // MediaBrowser until the engine is disposed.
        runOnMain(() -> {
            AudioServicePlugin.disposeFlutterEngine();
            return null;
        });
        await("AudioService destroyed during cleanup", () -> AudioService.instance == null, TIMEOUT_MS);
    }

    /**
     * P1(b). After the first engine is disposed, the process keeps running and a replacement
     * engine is created, as when MediaButtonReceiver or Android Auto binds the service. Commands
     * that reach the handler before the replacement engine's Dart side has called
     * AudioService.init must be delivered, as they are for the first engine of a process.
     *
     * <p>The engine is created and the commands are sent in one main-thread task, so Dart cannot
     * have registered its handler yet. Two commands are sent because a single early message is
     * held by Flutter's one-message channel buffer; a second one displaces it.</p>
     */
    @Test
    public void commandsSentBeforeReplacementEngineConfiguresAreDelivered() throws Exception {
        final int pid = Process.myPid();
        launchAndAwaitHandler();
        closeActivity();
        await("first engine disposed", () -> cachedEngine() == null, TIMEOUT_MS);
        final int firstGeneration = AudioServicePlugin.getFlutterEngineGeneration();

        runOnMain(() -> {
            AudioServicePlugin.getFlutterEngine(context);
            final AudioService.ServiceListener handler =
                    (AudioService.ServiceListener) AudioServicePlugin.audioHandlerInterface();
            handler.onClick(MediaButton.media);
            handler.onSeekTo(0);
            return null;
        });
        assertEquals("A replacement engine must have been created", firstGeneration + 1,
                AudioServicePlugin.getFlutterEngineGeneration());

        await("The media button sent to the replacement engine never started playback",
                () -> AudioService.instance != null && AudioService.instance.isPlaying()
                        && isAudioServiceStartedInForeground(), TIMEOUT_MS);
        assertEquals("Application PID changed", pid, Process.myPid());
    }

    /**
     * P1(c). The service is stopped from outside while playing (the handler keeps playing for the
     * disposal delay), and a media button arrives inside the delay. The press must not leave a
     * started AudioService waiting for startForeground(), which Android punishes with a crash, and
     * must pause the handler like any pause.
     */
    @Test
    public void mediaButtonInsideDisposalWindowNeverLeavesForegroundStartPending() throws Exception {
        final MediaControllerCompat controller = launchAndAwaitHandler();
        runOnMain(() -> {
            controller.getTransportControls().play();
            return null;
        });
        await("AudioService did not enter its playing foreground state",
                () -> AudioService.instance != null && AudioService.instance.isPlaying()
                        && isAudioServiceStartedInForeground(), TIMEOUT_MS);
        closeActivity();
        assertNotNull("A playing AudioService must survive its Activity closing", AudioService.instance);

        context.stopService(new Intent(context, AudioService.class));
        await("AudioService was not destroyed after being stopped", () -> AudioService.instance == null, TIMEOUT_MS);
        // Delivered to the app's own MediaButtonReceiver as MediaSessionService does when no
        // session is left: `cmd media_session dispatch` would target whichever app Android last
        // recorded as the media button receiver.
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(new ComponentName(context, MediaButtonReceiver.class))
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
        }

        // Poll through Android's startForeground() deadline; the process dies if it expires.
        final long end = SystemClock.elapsedRealtime() + 12_000;
        long pendingSince = -1;
        while (SystemClock.elapsedRealtime() < end) {
            final boolean pending = shell("dumpsys activity services " + context.getPackageName())
                    .contains("fgRequired=true");
            if (pending && pendingSince < 0) pendingSince = SystemClock.elapsedRealtime();
            if (!pending) pendingSince = -1;
            if (pendingSince >= 0 && SystemClock.elapsedRealtime() - pendingSince > FOREGROUND_DEADLINE_MS) {
                fail("AudioService was started with startForegroundService() and did not call "
                        + "startForeground() within " + FOREGROUND_DEADLINE_MS + " ms");
            }
            SystemClock.sleep(250);
        }
        // The press toggles the still-playing handler to paused, like any pause.
        assertTrue("The media button did not leave a paused AudioService; service=" + AudioService.instance,
                AudioService.instance != null && !AudioService.instance.isPlaying());
    }

    /**
     * P6(a). A service destroyed while its handler plays is recreated inside the disposal delay
     * (here by the Activity returning). The handler still reports playing, so the recreated service
     * must be started in the foreground again rather than waiting for a state change that a
     * steadily playing handler never sends.
     */
    @Test
    public void serviceRecreatedWhilePlayingReturnsToForeground() throws Exception {
        final MediaControllerCompat controller = launchAndAwaitHandler();
        runOnMain(() -> {
            controller.getTransportControls().play();
            return null;
        });
        await("AudioService did not enter its playing foreground state",
                () -> AudioService.instance != null && AudioService.instance.isPlaying()
                        && isAudioServiceStartedInForeground(), TIMEOUT_MS);
        closeActivity();
        final AudioService first = AudioService.instance;
        assertNotNull("A playing AudioService must survive its Activity closing", first);

        context.stopService(new Intent(context, AudioService.class));
        await("AudioService was not destroyed after being stopped", () -> AudioService.instance == null, TIMEOUT_MS);
        scenario = ActivityScenario.launch(AudioServiceActivity.class);
        await("AudioService was not recreated", () -> AudioService.instance != null && AudioService.instance != first,
                TIMEOUT_MS);
        assertNotNull("The engine must survive a recreation inside the disposal delay", cachedEngine());
        assertTrue("The recreated service must restore the playing state", AudioService.instance.isPlaying());

        await("The recreated AudioService reports playing but was not started in the foreground",
                () -> AudioService.instance != null && isAudioServiceStartedInForeground(), FOREGROUND_DEADLINE_MS);
    }

    /**
     * Android refuses the foreground start, here from startForeground() after
     * startForegroundService() went through. Nothing may be left half established: no playing
     * state, no wake lock, no foreground service. The media session stays active so media buttons
     * still reach the handler, and Dart is told once.
     */
    @Test
    public void refusedForegroundStartLeavesNothingHalfEstablished() throws Exception {
        assumeTrue("ForegroundServiceStartNotAllowedException needs API 31", Build.VERSION.SDK_INT >= 31);
        final ScriptedPromoter promoter = installPromoter(ScriptedPromoter.Mode.REFUSE);
        final MediaControllerCompat controller = launchAndAwaitHandler();
        final AudioService service = AudioService.instance;

        play(controller);
        await("The refusal was not reported to Dart", () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);
        assertEquals("FOREGROUND_START_REFUSED", lastAsyncError(controller));
        assertEquals(1, promoter.attempts.get());
        assertTrue("The handler must still report playing", service.isPlaying());
        assertFalse("A refused start must not leave the playing state entered", service.isPlayingStateEntered());
        assertFalse("A refused start must not leave the wake lock held", service.isWakeLockHeld());
        assertTrue("The media session must stay active while the handler plays", service.isMediaSessionActive());
        assertFalse("A refused start must not leave a foreground service", isAudioServiceStartedInForeground());
    }

    /**
     * After a refusal, live updates from a handler that keeps playing must not retry the start:
     * each would be refused again and reported to Dart again.
     */
    @Test
    public void liveUpdatesWhilePlayingDoNotRetryARefusedStart() throws Exception {
        assumeTrue("ForegroundServiceStartNotAllowedException needs API 31", Build.VERSION.SDK_INT >= 31);
        final ScriptedPromoter promoter = installPromoter(ScriptedPromoter.Mode.REFUSE);
        final MediaControllerCompat controller = launchAndAwaitHandler();
        play(controller);
        await("The refusal was not reported to Dart", () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);

        sendLiveUpdates(controller);

        assertEquals("Live updates retried the refused start", 1, promoter.attempts.get());
        assertFalse(AudioService.instance.isPlayingStateEntered());
        assertFalse(AudioService.instance.isWakeLockHeld());
        assertHoldsFor("The refusal must be reported to Dart once",
                () -> asyncErrorCount(controller) == 1, 1_000);
    }

    /**
     * A refused start is retried when the Activity resumes, which is when Android allows it again.
     * One resume makes exactly one attempt, and that attempt establishes the playing state.
     */
    @Test
    public void activityResumeRetriesARefusedStartOnce() throws Exception {
        assumeTrue("ForegroundServiceStartNotAllowedException needs API 31", Build.VERSION.SDK_INT >= 31);
        final ScriptedPromoter promoter = installPromoter(ScriptedPromoter.Mode.REFUSE);
        final MediaControllerCompat controller = launchAndAwaitHandler();
        play(controller);
        await("The refusal was not reported to Dart", () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);
        assertEquals(1, promoter.attempts.get());

        promoter.mode = ScriptedPromoter.Mode.ALLOW;
        pauseAndResumeActivity();

        await("The resumed Activity did not bring the playing AudioService into the foreground",
                () -> AudioService.instance.isPlayingStateEntered() && isAudioServiceStartedInForeground(),
                FOREGROUND_DEADLINE_MS);
        assertEquals("One resume must make exactly one attempt", 2, promoter.attempts.get());
        assertTrue("The playing state must hold the wake lock", AudioService.instance.isWakeLockHeld());
        assertEquals("A retry must not report to Dart", 1, asyncErrorCount(controller));
    }

    /**
     * A retry runs from the Activity's lifecycle callback, where a thrown exception would crash the
     * app. A failure other than a refusal must be reported instead (logged and delivered to
     * AudioService.asyncError), recorded so that later resumes do not retry it, and tried again by
     * a new play.
     */
    @Test
    public void failedRetryOnActivityResumeIsReportedNotThrown() throws Exception {
        assumeTrue("ForegroundServiceStartNotAllowedException needs API 31", Build.VERSION.SDK_INT >= 31);
        final ScriptedPromoter promoter = installPromoter(ScriptedPromoter.Mode.REFUSE);
        final MediaControllerCompat controller = launchAndAwaitHandler();
        final int pid = Process.myPid();
        play(controller);
        await("The refusal was not reported to Dart", () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);

        // Thrown out of onActivityResumed(), this would kill the process running this test.
        promoter.mode = ScriptedPromoter.Mode.FAIL;
        pauseAndResumeActivity();
        assertEquals("The resume must retry the refused start once", 2, promoter.attempts.get());
        assertEquals("Application PID changed", pid, Process.myPid());
        final AudioService service = AudioService.instance;
        assertNotNull("AudioService must survive a failed retry", service);
        assertTrue("The handler must still report playing", service.isPlaying());
        assertFalse(service.isPlayingStateEntered());
        assertFalse(service.isWakeLockHeld());
        await("The failed retry did not reach AudioService.asyncError",
                () -> asyncErrorCount(controller) == 2, TIMEOUT_MS);
        assertEquals("AudioService.retryForegroundIfPlaying", lastAsyncError(controller));

        pauseAndResumeActivity();
        assertEquals("A failed retry must not be retried by the next resume", 2, promoter.attempts.get());

        pause(controller);
        play(controller);
        await("The failure of the new play did not reach Dart", () -> asyncErrorCount(controller) == 3, TIMEOUT_MS);
        assertEquals(SIMULATED_FAILURE, lastAsyncError(controller));
        assertEquals("A new play must try again, once", 3, promoter.attempts.get());
    }

    /**
     * Any other failure, such as the IllegalStateException subclasses Android 14+ throws for a
     * missing or invalid foreground service type, is a configuration error. It must reach Dart and
     * must not be retried by live updates or an Activity resume; only a new play tries again.
     */
    @Test
    public void foregroundStartFailureReachesDartAndIsNotRetried() throws Exception {
        final ScriptedPromoter promoter = installPromoter(ScriptedPromoter.Mode.FAIL);
        final MediaControllerCompat controller = launchAndAwaitHandler();
        play(controller);
        await("The failure did not reach Dart", () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);
        assertEquals(SIMULATED_FAILURE, lastAsyncError(controller));
        assertEquals(1, promoter.attempts.get());
        assertFalse(AudioService.instance.isPlayingStateEntered());
        assertFalse(AudioService.instance.isWakeLockHeld());

        sendLiveUpdates(controller);
        pauseAndResumeActivity();
        assertEquals("A failed start must not be retried by live updates or a resume",
                1, promoter.attempts.get());

        pause(controller);
        play(controller);
        await("The failure of the new play did not reach Dart", () -> asyncErrorCount(controller) == 2, TIMEOUT_MS);
        assertEquals("A new play must try again, once", 2, promoter.attempts.get());
    }

    private static ScriptedPromoter installPromoter(ScriptedPromoter.Mode mode) {
        final ScriptedPromoter promoter = new ScriptedPromoter(mode);
        AudioService.foregroundPromoter = promoter;
        return promoter;
    }

    private void play(MediaControllerCompat controller) throws Exception {
        runOnMain(() -> {
            controller.getTransportControls().play();
            return null;
        });
        await("The handler did not start playing",
                () -> AudioService.instance != null && AudioService.instance.isPlaying(), TIMEOUT_MS);
    }

    private void pause(MediaControllerCompat controller) throws Exception {
        runOnMain(() -> {
            controller.getTransportControls().pause();
            return null;
        });
        await("The handler did not pause",
                () -> AudioService.instance != null && !AudioService.instance.isPlaying(), TIMEOUT_MS);
    }

    /** Pauses and resumes the Activity, which runs the plugin's resume retry once. */
    private void pauseAndResumeActivity() {
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Seeks {@link #LIVE_UPDATES} times; each seek is a live state update that keeps playing. */
    private void sendLiveUpdates(MediaControllerCompat controller) throws Exception {
        runOnMain(() -> {
            for (int i = 1; i <= LIVE_UPDATES; i++) {
                controller.getTransportControls().seekTo(i * 1000L);
            }
            return null;
        });
        await("The live updates were not all applied", () -> runOnMain(() -> {
            final PlaybackStateCompat state = controller.getPlaybackState();
            return state != null && state.getPosition() == LIVE_UPDATES * 1000L;
        }), TIMEOUT_MS);
        assertTrue("The handler must keep playing through the updates", AudioService.instance.isPlaying());
    }

    /** Errors that reached AudioService.asyncError, as counted by the harness handler. */
    private long asyncErrorCount(MediaControllerCompat controller) throws Exception {
        return runOnMain(() -> {
            final MediaMetadataCompat metadata = controller.getMetadata();
            return metadata == null ? 0L : metadata.getLong("asyncErrorCount");
        });
    }

    private String lastAsyncError(MediaControllerCompat controller) throws Exception {
        return runOnMain(() -> controller.getMetadata().getString("lastAsyncError"));
    }

    /**
     * Stands in for the framework's foreground calls. REFUSE throws what Android 12+ throws for a
     * start from the background; FAIL throws a plain IllegalStateException, as Android 14+ does for
     * a missing or invalid foreground service type. Neither makes a framework call, so no pending
     * startForegroundService() is left behind; ALLOW makes the real calls.
     */
    private static final class ScriptedPromoter implements AudioService.ForegroundPromoter {
        enum Mode { ALLOW, REFUSE, FAIL }

        volatile Mode mode;
        final AtomicInteger attempts = new AtomicInteger();

        ScriptedPromoter(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void startForegroundService(AudioService service) {
            attempts.incrementAndGet();
            if (mode == Mode.ALLOW) {
                AudioService.FRAMEWORK_FOREGROUND_PROMOTER.startForegroundService(service);
            }
        }

        @Override
        public void startForeground(AudioService service, int id, Notification notification) {
            switch (mode) {
                case ALLOW:
                    AudioService.FRAMEWORK_FOREGROUND_PROMOTER.startForeground(service, id, notification);
                    break;
                case REFUSE:
                    throw new ForegroundServiceStartNotAllowedException("simulated background start refusal");
                case FAIL:
                    throw new IllegalStateException(SIMULATED_FAILURE);
            }
        }
    }

    /** Launches the Activity and waits until the harness handler has published its media item. */
    private MediaControllerCompat launchAndAwaitHandler() throws Exception {
        scenario = ActivityScenario.launch(AudioServiceActivity.class);
        await("AudioService was not created", () -> AudioService.instance != null, TIMEOUT_MS);
        final AudioService service = AudioService.instance;
        final MediaControllerCompat controller =
                runOnMain(() -> new MediaControllerCompat(context, service.getSessionToken()));
        await("The harness handler did not publish its media item",
                () -> runOnMain(() -> controller.getMetadata() != null), 60_000);
        return controller;
    }

    private void closeActivity() {
        scenario.close();
        scenario = null;
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static Object cachedEngine() {
        return FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId());
    }

    /** getRunningServices() is deprecated but still reports the caller's own services. */
    @SuppressWarnings("deprecation")
    private boolean isAudioServiceStartedInForeground() {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo info : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (AudioService.class.getName().equals(info.service.getClassName())) {
                return info.started && info.foreground;
            }
        }
        return false;
    }

    private static String shell(String command) throws IOException {
        final ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        final StringBuilder out = new StringBuilder();
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.append(new String(buffer, 0, n));
        } finally {
            pfd.close();
        }
        return out.toString();
    }

    private <T> T runOnMain(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        main.post(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(String failureMessage, Condition condition, long timeoutMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.holds()) return;
            SystemClock.sleep(25);
        }
        fail(failureMessage + "; pid=" + Process.myPid() + ", service=" + AudioService.instance
                + ", engineGeneration=" + AudioServicePlugin.getFlutterEngineGeneration());
    }

    private static void assertHoldsFor(String failureMessage, Condition condition, long durationMs) throws Exception {
        final long end = SystemClock.elapsedRealtime() + durationMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (!condition.holds()) fail(failureMessage);
            SystemClock.sleep(25);
        }
    }
}
