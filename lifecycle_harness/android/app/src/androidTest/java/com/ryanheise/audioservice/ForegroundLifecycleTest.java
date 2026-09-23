package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;
import android.view.KeyEvent;

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

/**
 * Foreground-service contract of AudioService across engine disposal and service recreation.
 *
 * <p>The instrumented process counts as foreground, so Android never refuses a foreground-service
 * start here. These tests cover what the plugin itself does: whether a playing handler ends up with
 * a started, foreground AudioService, and whether handler commands reach Dart.</p>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ForegroundLifecycleTest {
    private static final long TIMEOUT_MS = 30_000;
    /** Well below Android's startForeground() deadline (10 s on API 31+). */
    private static final long FOREGROUND_DEADLINE_MS = 5_000;

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
}
