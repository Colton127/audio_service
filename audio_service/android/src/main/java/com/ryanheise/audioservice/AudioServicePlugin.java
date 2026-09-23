package com.ryanheise.audioservice;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.UiThread;
import androidx.core.app.NotificationCompat;

import android.support.v4.media.MediaBrowserCompat;

import androidx.media.MediaBrowserServiceCompat;

import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.android.FlutterFragmentActivity;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.NewIntentListener;
import io.flutter.plugin.common.BinaryMessenger;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;

import android.net.Uri;
import android.util.Log;

import static com.ryanheise.audioservice.AudioServiceLifecycleLog.hashOf;
import static com.ryanheise.audioservice.AudioServiceLifecycleLog.log;

/**
 * AudioservicePlugin
 */
public class AudioServicePlugin implements FlutterPlugin, ActivityAware {
    private static String flutterEngineId = "audio_service_engine";
    /** Must be called BEFORE any FlutterEngine is created. e.g. in Application class. */
    public static void setFlutterEngineId(String id) {
        flutterEngineId = id;
    }
    public static String getFlutterEngineId() {
        return flutterEngineId;
    }

    /**
     * Process-local counter incremented each time a shared FlutterEngine is
     * created. Diagnostic only. It is never reset, so it stays valid while
     * onDetachedFromEngine() runs during the destruction of an engine.
     */
    private static volatile int flutterEngineGeneration;

    /** Generation of the most recently created shared FlutterEngine. */
    static int getFlutterEngineGeneration() {
        return flutterEngineGeneration;
    }

    /** Best-effort description of who asked for the shared FlutterEngine. */
    private static String requesterOf(Context context) {
        if (context instanceof Activity) return "activity";
        if (context instanceof AudioService) return "service";
        return "other";
    }

    public static synchronized FlutterEngine getFlutterEngine(Context context) {
        final String requester = requesterOf(context);
        // Whoever asks for the engine needs it alive, so a disposal deferred
        // from a previous AudioService.onDestroy() is no longer wanted.
        cancelFlutterEngineDisposal("engine_requested_by_" + requester);
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(flutterEngineId);
        if (flutterEngine == null) {
            // Bump the generation before the engine is constructed, since the
            // constructor synchronously triggers onAttachedToEngine.
            flutterEngineGeneration++;
            log("flutter_engine_create_begin", "generation=" + flutterEngineGeneration
                    + " requester=" + requester);
            // XXX: The constructor triggers onAttachedToEngine so this variable doesn't help us.
            // Maybe need a boolean flag to tell us we're currently loading the main flutter engine.
            flutterEngine = new FlutterEngine(context.getApplicationContext());
            String initialRoute = null;
            if (context instanceof FlutterActivity) {
                final FlutterActivity activity = (FlutterActivity)context;
                initialRoute = activity.getInitialRoute();
                if (initialRoute == null) {
                    if (activity.shouldHandleDeeplinking()) {
                        Uri data = activity.getIntent().getData();
                        if (data != null) {
                            initialRoute = data.getPath();
                            if (data.getQuery() != null && !data.getQuery().isEmpty()) {
                                initialRoute += "?" + data.getQuery();
                            }
                        }
                    }
                }
            } else if (context instanceof AudioServiceFragmentActivity) {
                final AudioServiceFragmentActivity activity = (AudioServiceFragmentActivity)context;
                initialRoute = activity.getInitialRoute();
                if (initialRoute == null) {
                    if (activity.shouldHandleDeeplinking()) {
                        Uri data = activity.getIntent().getData();
                        if (data != null) {
                            initialRoute = data.getPath();
                            if (data.getQuery() != null && !data.getQuery().isEmpty()) {
                                initialRoute += "?" + data.getQuery();
                            }
                        }
                    }
                }
            }
            if (initialRoute == null) {
                initialRoute = "/";
            }
            flutterEngine.getNavigationChannel().setInitialRoute(initialRoute);
            flutterEngine.getDartExecutor().executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault());
            FlutterEngineCache.getInstance().put(flutterEngineId, flutterEngine);
            log("flutter_engine_created", "generation=" + flutterEngineGeneration
                    + " engineHash=" + hashOf(flutterEngine)
                    + " messengerHash=" + hashOf(flutterEngine.getDartExecutor())
                    + " requester=" + requester);
        } else {
            log("flutter_engine_reused", "generation=" + flutterEngineGeneration
                    + " engineHash=" + hashOf(flutterEngine)
                    + " messengerHash=" + hashOf(flutterEngine.getDartExecutor())
                    + " requester=" + requester);
        }
        return flutterEngine;
    }

    public static synchronized void disposeFlutterEngine() {
        log("flutter_engine_dispose_requested", "generation=" + flutterEngineGeneration
                + " engineHash=" + hashOf(FlutterEngineCache.getInstance().get(flutterEngineId)));
        for (ClientInterface clientInterface : clientInterfaces) {
            if (clientInterface.activity != null) {
                // Don't destroy the engine if a new activity started and
                // bound to the service in the time since the previous activity
                // unbound from it.
                log("flutter_engine_dispose_skipped", "generation=" + flutterEngineGeneration
                        + " reason=activity_attached");
                return;
            }
        }
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(flutterEngineId);
        if (flutterEngine != null) {
            // Captured before destroy() so both lines correlate with the same
            // messenger identity.
            final String messengerHash = hashOf(flutterEngine.getDartExecutor());
            log("flutter_engine_destroy_begin", "generation=" + flutterEngineGeneration
                    + " engineHash=" + hashOf(flutterEngine)
                    + " messengerHash=" + messengerHash);
            flutterEngine.destroy();
            FlutterEngineCache.getInstance().remove(flutterEngineId);
            log("flutter_engine_destroyed", "generation=" + flutterEngineGeneration
                    + " engineHash=" + hashOf(flutterEngine)
                    + " messengerHash=" + messengerHash);
        } else {
            log("flutter_engine_dispose_skipped", "generation=" + flutterEngineGeneration
                    + " reason=no_engine");
        }
    }

    /**
     * How long after an AudioService instance is destroyed the shared
     * FlutterEngine is kept before being disposed. Long enough for a service
     * (re)creation that the system had already queued to reach onCreate() and
     * cancel the disposal.
     */
    private static final long ENGINE_DISPOSAL_DELAY_MS = 1000;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingEngineDisposal;

    /**
     * Disposes the shared FlutterEngine after {@link #ENGINE_DISPOSAL_DELAY_MS},
     * unless the engine is requested again (see {@link #getFlutterEngine}) or
     * an AudioService instance exists by then.
     * <p>
     * Disposing synchronously from AudioService.onDestroy() created a
     * self-sustaining loop whenever the system had already queued the
     * creation of the next service instance: destroying the engine detached
     * the plugin, which posted an unbind of the old MediaBrowser, while the
     * queued onCreate() built a fresh engine whose plugin posted a bind. The
     * unbind then tore down the new service and the bind created yet another
     * one, bootstrapping a FlutterEngine on every cycle. Deferring the
     * disposal lets that queued onCreate() find the engine in the cache and
     * cancel the disposal, so the cycle never starts.
     */
    private static synchronized void scheduleFlutterEngineDisposal() {
        cancelFlutterEngineDisposal("rescheduled");
        pendingEngineDisposal = () -> {
            synchronized (AudioServicePlugin.class) {
                pendingEngineDisposal = null;
                if (AudioService.instance != null) {
                    log("flutter_engine_dispose_skipped", "generation=" + flutterEngineGeneration
                            + " reason=service_recreated");
                    return;
                }
                disposeFlutterEngine();
            }
        };
        log("flutter_engine_dispose_scheduled", "generation=" + flutterEngineGeneration
                + " delayMs=" + ENGINE_DISPOSAL_DELAY_MS);
        mainHandler.postDelayed(pendingEngineDisposal, ENGINE_DISPOSAL_DELAY_MS);
    }

    private static synchronized void cancelFlutterEngineDisposal(String reason) {
        if (pendingEngineDisposal == null) return;
        mainHandler.removeCallbacks(pendingEngineDisposal);
        pendingEngineDisposal = null;
        log("flutter_engine_dispose_cancelled", "generation=" + flutterEngineGeneration
                + " reason=" + reason);
    }

    private static final String CHANNEL_CLIENT = "com.ryanheise.audio_service.client.methods";
    private static final String CHANNEL_HANDLER = "com.ryanheise.audio_service.handler.methods";

    private static final Set<ClientInterface> clientInterfaces = new HashSet<>();
    private static ClientInterface mainClientInterface;
    private static AudioHandlerInterface audioHandlerInterface;
    private static final long bootTime;
    private static Result configureResult;
    private static boolean flutterReady;

    static {
        bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    static AudioHandlerInterface audioHandlerInterface() throws Exception {
        if (audioHandlerInterface == null) throw new Exception("Background audio task not running");
        return audioHandlerInterface;
    }

    private static MediaBrowserCompat mediaBrowser;
    private static MediaControllerCompat mediaController;
    private static final MediaControllerCompat.Callback controllerCallback = new MediaControllerCompat.Callback() {
//        @Override
//        public void onMetadataChanged(MediaMetadataCompat metadata) {
//            Map<String, Object> map = new HashMap<>();
//            map.put("mediaItem", mediaMetadata2raw(metadata));
//            invokeClientMethod("onMediaItemChanged", map);
//        }
//
//        @Override
//        public void onPlaybackStateChanged(PlaybackStateCompat state) {
//            // On the native side, we represent the update time relative to the boot time.
//            // On the flutter side, we represent the update time relative to the epoch.
//            long updateTimeSinceBoot = state.getLastPositionUpdateTime();
//            long updateTimeSinceEpoch = bootTime + updateTimeSinceBoot;
//            Map<String, Object> stateMap = new HashMap<>();
//            stateMap.put("processingState", AudioService.instance.getProcessingState().ordinal());
//            stateMap.put("playing", AudioService.instance.isPlaying());
//            stateMap.put("controls", new ArrayList<>());
//            long actionBits = state.getActions();
//            List<Object> systemActions = new ArrayList<>();
//            for (int actionIndex = 0; actionIndex < 64; actionIndex++) {
//                if ((actionBits & (1 << actionIndex)) != 0) {
//                    systemActions.add(actionIndex);
//                }
//            }
//            stateMap.put("systemActions", systemActions);
//            stateMap.put("updatePosition", state.getPosition());
//            stateMap.put("bufferedPosition", state.getBufferedPosition());
//            stateMap.put("speed", state.getPlaybackSpeed());
//            stateMap.put("updateTime", updateTimeSinceEpoch);
//            stateMap.put("repeatMode", AudioService.instance.getRepeatMode());
//            stateMap.put("shuffleMode", AudioService.instance.getShuffleMode());
//            Map<String, Object> map = new HashMap<>();
//            map.put("state", stateMap);
//            invokeClientMethod("onPlaybackStateChanged", map);
//        }
//
//        @Override
//        public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
//            Map<String, Object> map = new HashMap<>();
//            map.put("queue", queue2raw(queue));
//            invokeClientMethod("onQueueChanged", map);
//        }
    };
//    private static void invokeClientMethod(String method, Object arg) {
//        for (ClientInterface clientInterface : clientInterfaces) {
//            clientInterface.channel.invokeMethod(method, arg);
//        }
//    }

    //
    // INSTANCE FIELDS AND METHODS
    //

    private Context applicationContext;
    private FlutterPluginBinding flutterPluginBinding;
    private ActivityPluginBinding activityPluginBinding;
    private Application.ActivityLifecycleCallbacks foregroundRetryCallbacks;
    private Application foregroundRetryApplication;
    private NewIntentListener newIntentListener;
    private ClientInterface clientInterface;
    private final MediaBrowserCompat.ConnectionCallback connectionCallback = new MediaBrowserCompat.ConnectionCallback() {
        @Override
        public void onConnected() {
            log("media_browser_connected", "generation=" + flutterEngineGeneration
                    + " browserHash=" + hashOf(mediaBrowser)
                    + " serviceGeneration=" + serviceGenerationOrNone()
                    + " pluginDetached=" + (applicationContext == null));
            if (applicationContext == null) return; 
            try {
                MediaSessionCompat.Token token = mediaBrowser.getSessionToken();
                // A reconnection (e.g. after the service was recreated and the
                // browser resumed from onConnectionSuspended) yields a new
                // session token, so release the controller of the old session.
                if (mediaController != null) {
                    mediaController.unregisterCallback(controllerCallback);
                }
                mediaController = new MediaControllerCompat(applicationContext, token);
                Activity activity = mainClientInterface != null ? mainClientInterface.activity : null;
                if (activity != null) {
                    MediaControllerCompat.setMediaController(activity, mediaController);
                }
                mediaController.registerCallback(controllerCallback);
                // PlaybackStateCompat state = mediaController.getPlaybackState();
                // controllerCallback.onPlaybackStateChanged(state);
                // MediaMetadataCompat metadata = mediaController.getMetadata();
                // controllerCallback.onQueueChanged(mediaController.getQueue());
                // controllerCallback.onMetadataChanged(metadata);
                if (configureResult != null) {
                    configureResult.success(mapOf());
                    configureResult = null;
                }
            } catch (Exception e) {
                Log.e(AudioServiceLifecycleLog.TAG, "onConnected error: " + e.getMessage(), e);
                if (configureResult != null) {
                    configureResult.error("onConnected error: " + e.getMessage(), null, null);
                } else {
                    clientInterface.setServiceConnectionFailed(true);
                }
            }
        }

        @Override
        public void onConnectionSuspended() {
            // TODO: Handle this
            log("media_browser_connection_suspended", "generation=" + flutterEngineGeneration);
        }

        @Override
        public void onConnectionFailed() {
            log("media_browser_connection_failed", "generation=" + flutterEngineGeneration
                    + " browserHash=" + hashOf(mediaBrowser)
                    + " serviceGeneration=" + serviceGenerationOrNone());
            if (configureResult != null) {
                configureResult.error("Unable to bind to AudioService. Please ensure you have declared a <service> element as described in the README.", null, null);
            } else {
                clientInterface.setServiceConnectionFailed(true);
            }
        }
    };


    //
    // FlutterPlugin callbacks
    //

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        log("plugin_attached_to_engine", "generation=" + flutterEngineGeneration
                + " messengerHash=" + hashOf(binding.getBinaryMessenger()));
        flutterPluginBinding = binding;
        clientInterface = new ClientInterface(flutterPluginBinding.getBinaryMessenger());
        clientInterface.setContext(flutterPluginBinding.getApplicationContext());
        clientInterfaces.add(clientInterface);
        if (applicationContext == null) {
            applicationContext = flutterPluginBinding.getApplicationContext();
        }
        if (audioHandlerInterface == null) {
            // We don't know yet whether this is the right engine that hosts the AudioHandler,
            // but we need to register a MethodCallHandler now just in case. If we're wrong, we
            // detect and correct this when receiving the "configure" message.
            audioHandlerInterface = new AudioHandlerInterface(flutterPluginBinding.getBinaryMessenger());
            AudioService.init(audioHandlerInterface);
        }
        if (mediaBrowser == null) {
            connect("engine_attached");
        }
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        log("plugin_detached_from_engine", "generation=" + flutterEngineGeneration
                + " messengerHash=" + hashOf(binding.getBinaryMessenger()));
        if (clientInterfaces.size() == 1) {
            disconnect("engine_detached");
        }
        clientInterfaces.remove(clientInterface);
        clientInterface.setContext(null);
        clientInterface = null;
        applicationContext = null;
        if (audioHandlerInterface != null
                && audioHandlerInterface.messenger == flutterPluginBinding.getBinaryMessenger()) {
            audioHandlerInterface.destroy();
            audioHandlerInterface = null;
            // A replacement engine must queue handler calls until its own
            // Dart side configures, as the first engine of a process does:
            // sent earlier, they reach a channel with no Dart handler, where
            // Flutter buffers a single message and discards the rest.
            flutterReady = false;
            // The handler interface is owned by the engine, not by an
            // AudioService instance, so it is unregistered from the service
            // here rather than in AudioService.onDestroy().
            AudioService.init(null);
            log("audio_handler_interface_destroyed", "generation=" + flutterEngineGeneration
                    + " messengerHash=" + hashOf(binding.getBinaryMessenger()));
        }
        flutterPluginBinding = null;
    }

    //
    // ActivityAware callbacks
    //

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        logActivityEvent("activity_attached", binding.getActivity());
        activityPluginBinding = binding;
        clientInterface.setActivity(binding.getActivity());
        clientInterface.setContext(binding.getActivity());
        // Verify that the app is configured with the correct FlutterEngine.
        FlutterEngine sharedEngine = getFlutterEngine(binding.getActivity());
        clientInterface.setWrongEngineDetected(flutterPluginBinding.getBinaryMessenger() != sharedEngine.getDartExecutor());
        mainClientInterface = clientInterface;
        registerOnNewIntentListener();
        registerForegroundRetry(binding.getActivity());
        if (mediaController != null) {
            MediaControllerCompat.setMediaController(mainClientInterface.activity, mediaController);
        }
        if (mediaBrowser == null) {
            connect("activity_attached");
        }

        Activity activity = mainClientInterface.activity;
        if (clientInterface.wasLaunchedFromRecents()) {
            // We do this to avoid using the old intent.
            activity.setIntent(new Intent(Intent.ACTION_MAIN));
        }
        sendNotificationClicked();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        logActivityEvent("activity_detached_config_change",
                activityPluginBinding != null ? activityPluginBinding.getActivity() : null);
        activityPluginBinding.removeOnNewIntentListener(newIntentListener);
        activityPluginBinding = null;
        unregisterForegroundRetry();
        clientInterface.setActivity(null);
        clientInterface.setContext(flutterPluginBinding.getApplicationContext());
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        logActivityEvent("activity_reattached_config_change", binding.getActivity());
        activityPluginBinding = binding;
        clientInterface.setActivity(binding.getActivity());
        clientInterface.setContext(binding.getActivity());
        registerOnNewIntentListener();
        registerForegroundRetry(binding.getActivity());
    }

    @Override
    public void onDetachedFromActivity() {
        logActivityEvent("activity_detached",
                activityPluginBinding != null ? activityPluginBinding.getActivity() : null);
        activityPluginBinding.removeOnNewIntentListener(newIntentListener);
        activityPluginBinding = null;
        newIntentListener = null;
        unregisterForegroundRetry();
        clientInterface.setActivity(null);
        clientInterface.setContext(flutterPluginBinding.getApplicationContext());
        if (clientInterfaces.size() == 1) {
            // This unbinds from the service allowing AudioService.onDestroy to
            // happen which in turn schedules the disposal of the FlutterEngine.
            disconnect("activity_detached");
        }
        if (clientInterface == mainClientInterface) {
            mainClientInterface = null;
        }
    }

    /**
     * While an Activity is resumed the app may start a foreground service, so
     * a playing AudioService whose start from the background was refused
     * gets it then (see AudioService.retryForegroundIfPlaying).
     */
    private void registerForegroundRetry(final Activity activity) {
        unregisterForegroundRetry();
        foregroundRetryCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity resumed) {
                if (resumed == activity && AudioService.instance != null) {
                    AudioService.instance.retryForegroundIfPlaying("activity_resumed");
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity a) {}
        };
        foregroundRetryApplication = activity.getApplication();
        foregroundRetryApplication.registerActivityLifecycleCallbacks(foregroundRetryCallbacks);
    }

    private void unregisterForegroundRetry() {
        if (foregroundRetryCallbacks == null) return;
        foregroundRetryApplication.unregisterActivityLifecycleCallbacks(foregroundRetryCallbacks);
        foregroundRetryCallbacks = null;
        foregroundRetryApplication = null;
    }

    /** Generation of the live AudioService instance, or {@code none}. */
    private static String serviceGenerationOrNone() {
        final AudioService service = AudioService.instance;
        return service == null ? "none" : String.valueOf(service.getServiceGeneration());
    }

    private static void logActivityEvent(String event, Activity activity) {
        log(event, "generation=" + flutterEngineGeneration
                + " activity=" + (activity == null ? "none" : activity.getClass().getName())
                + " activityHash=" + hashOf(activity));
    }

    /**
     * Connects the shared MediaBrowser to AudioService. The bind itself is
     * posted to the main looper by the framework, so it happens after this
     * returns; {@code media_browser_connected} marks its completion.
     */
    private void connect(String reason) {
        if (mediaBrowser == null) {
            mediaBrowser = new MediaBrowserCompat(applicationContext,
                    new ComponentName(applicationContext, AudioService.class),
                    connectionCallback,
                    null);
            log("media_browser_connect_requested", "generation=" + flutterEngineGeneration
                    + " reason=" + reason
                    + " browserHash=" + hashOf(mediaBrowser)
                    + " messengerHash=" + hashOf(flutterPluginBinding != null ? flutterPluginBinding.getBinaryMessenger() : null)
                    + " serviceGeneration=" + serviceGenerationOrNone());
            mediaBrowser.connect();
        }
    }

    /**
     * Disconnects the shared MediaBrowser. Like the bind, the unbind is posted
     * to the main looper, so it reaches the service after this returns.
     */
    private void disconnect(String reason) {
        log("media_browser_disconnect_requested", "generation=" + flutterEngineGeneration
                + " reason=" + reason
                + " browserHash=" + hashOf(mediaBrowser)
                + " browserConnected=" + (mediaBrowser != null && mediaBrowser.isConnected())
                + " messengerHash=" + hashOf(flutterPluginBinding != null ? flutterPluginBinding.getBinaryMessenger() : null)
                + " serviceGeneration=" + serviceGenerationOrNone());
        Activity activity = mainClientInterface != null ? mainClientInterface.activity : null;
        if (activity != null) {
            // Since the activity enters paused state, we set the intent with ACTION_MAIN.
            activity.setIntent(new Intent(Intent.ACTION_MAIN));
        }

        if (mediaController != null) {
            mediaController.unregisterCallback(controllerCallback);
            mediaController = null;
        }
        if (mediaBrowser != null) {
            mediaBrowser.disconnect();
            mediaBrowser = null;
        }
    }

    private void registerOnNewIntentListener() {
        activityPluginBinding.addOnNewIntentListener(newIntentListener = (intent) -> {
            clientInterface.activity.setIntent(intent);
            sendNotificationClicked();
            return true;
        });
    }

    private void sendNotificationClicked() {
        Activity activity = clientInterface.activity;
        if (audioHandlerInterface != null && activity.getIntent().getAction() != null) {
            boolean clicked = activity.getIntent().getAction().equals(AudioService.NOTIFICATION_CLICK_ACTION);
            audioHandlerInterface.invokeMethod("onNotificationClicked", mapOf("clicked", clicked));
        }
    }

    private static class ClientInterface implements MethodCallHandler {
        private Context context;
        private Activity activity;
        public final BinaryMessenger messenger;
        private final MethodChannel channel;
        private boolean wrongEngineDetected;
        private boolean serviceConnectionFailed;

        // This is implemented in Dart already.
        // But we may need to bring this back if we want to connect to another process's media session.
//        private final MediaBrowserCompat.SubscriptionCallback subscriptionCallback = new MediaBrowserCompat.SubscriptionCallback() {
//            @Override
//            public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
//                Map<String, Object> map = new HashMap<String, Object>();
//                map.put("parentMediaId", parentId);
//                map.put("children", mediaItems2raw(children));
//                invokeClientMethod("onChildrenLoaded", map);
//            }
//        };

        public ClientInterface(BinaryMessenger messenger) {
            this.messenger = messenger;
            channel = new MethodChannel(messenger, CHANNEL_CLIENT);
            channel.setMethodCallHandler(this);
        }

        private void setContext(Context context) {
            this.context = context;
        }

        private void setActivity(Activity activity) {
            this.activity = activity;
        }

        public void setWrongEngineDetected(boolean value) {
            wrongEngineDetected = value;
        }

        public void setServiceConnectionFailed(boolean value) {
            serviceConnectionFailed = value;
        }

        // See: https://stackoverflow.com/questions/13135545/android-activity-is-using-old-intent-if-launching-app-from-recent-task
        protected boolean wasLaunchedFromRecents() {
            return (activity.getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY;
        }

        @Override
        public void onMethodCall(MethodCall call, final Result result) {
            try {
                if (wrongEngineDetected) {
                    throw new IllegalStateException("The Activity class declared in your AndroidManifest.xml is wrong or has not provided the correct FlutterEngine. Please see the README for instructions.");
                }
                switch (call.method) {
                case "configure":
                    if (serviceConnectionFailed) {
                        throw new IllegalStateException("Unable to bind to AudioService. Please ensure you have declared a <service> element as described in the README.");
                    }
                    log("flutter_configure", "generation=" + flutterEngineGeneration
                            + " flutterReadyBefore=" + flutterReady
                            + " activity=" + (activity != null)
                            + " serviceGeneration=" + serviceGenerationOrNone());
                    flutterReady = true;
                    Map<?, ?> args = (Map<?, ?>)call.arguments;
                    Map<?, ?> configMap = (Map<?, ?>)args.get("config");
                    AudioServiceConfig config = new AudioServiceConfig(context.getApplicationContext());
                    config.androidNotificationClickStartsActivity = (Boolean)configMap.get("androidNotificationClickStartsActivity");
                    config.androidNotificationOngoing = (Boolean)configMap.get("androidNotificationOngoing");
                    config.androidResumeOnClick = (Boolean)configMap.get("androidResumeOnClick");
                    config.androidNotificationChannelId = (String)configMap.get("androidNotificationChannelId");
                    config.androidNotificationChannelName = (String)configMap.get("androidNotificationChannelName");
                    config.androidNotificationChannelDescription = (String)configMap.get("androidNotificationChannelDescription");
                    config.notificationColor = configMap.get("notificationColor") == null ? -1 : getInt(configMap.get("notificationColor"));
                    config.androidNotificationIcon = (String)configMap.get("androidNotificationIcon");
                    config.androidShowNotificationBadge = (Boolean)configMap.get("androidShowNotificationBadge");
                    config.androidStopForegroundOnPause = (Boolean)configMap.get("androidStopForegroundOnPause");
                    config.androidStopForegroundOnCompleted = (Boolean)configMap.get("androidStopForegroundOnCompleted");
                    config.artDownscaleWidth = configMap.get("artDownscaleWidth") != null ? (Integer)configMap.get("artDownscaleWidth") : -1;
                    config.artDownscaleHeight = configMap.get("artDownscaleHeight") != null ? (Integer)configMap.get("artDownscaleHeight") : -1;
                    config.setBrowsableRootExtras((Map<?,?>)configMap.get("androidBrowsableRootExtras"));
                    if (activity != null) {
                        config.activityClassName = activity.getClass().getName();
                    }
                    config.save();
                    if (AudioService.instance != null) {
                        AudioService.instance.configure(config);
                    }
                    mainClientInterface = ClientInterface.this;
                    if (audioHandlerInterface == null) {
                        audioHandlerInterface = new AudioHandlerInterface(messenger);
                        AudioService.init(audioHandlerInterface);
                    } else {
                        if (audioHandlerInterface.messenger != messenger) {
                            // We've detected this is the real engine hosting the AudioHandler,
                            // so update AudioHandlerInterface to connect to it.
                            audioHandlerInterface.switchToMessenger(messenger);
                        }
                        audioHandlerInterface.invokePendingMethods();
                    }
                    if (mediaController != null) {
                        result.success(mapOf());
                    } else {
                        configureResult = result;
                    }
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
                result.error(e.getMessage(), null, null);
            }
        }
    }

    private static class AudioHandlerInterface implements MethodCallHandler, AudioService.ServiceListener {
        private static final int SILENCE_SAMPLE_RATE = 44100;
        public BinaryMessenger messenger;
        public MethodChannel channel;
        private AudioTrack silenceAudioTrack;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private List<MethodInvocation> methodInvocationQueue = new LinkedList<MethodInvocation>();

        public AudioHandlerInterface(BinaryMessenger messenger) {
            this.messenger = messenger;
            channel = new MethodChannel(messenger, CHANNEL_HANDLER);
            channel.setMethodCallHandler(this);
        }

        public void switchToMessenger(BinaryMessenger messenger) {
            channel.setMethodCallHandler(null);
            this.messenger = messenger;
            channel = new MethodChannel(messenger, CHANNEL_HANDLER);
            channel.setMethodCallHandler(this);
        }

        public void invokePendingMethods() {
            for (MethodInvocation mi : methodInvocationQueue) {
                channel.invokeMethod(mi.method, mi.arg, mi.result);
            }
            methodInvocationQueue.clear();
        }

        @Override
        public void onLoadChildren(final String parentMediaId, final MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result, Bundle options) {
            if (audioHandlerInterface != null) {
                Map<String, Object> args = new HashMap<>();
                args.put("parentMediaId", parentMediaId);
                args.put("options", bundleToMap(options));
                audioHandlerInterface.invokeMethod("getChildren", args, new MethodChannel.Result() {
                    @Override
                    public void error(String errorCode, String errorMessage, Object errorDetails) {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void notImplemented() {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void success(Object obj) {
                        Map<?, ?> response = (Map<?, ?>)obj;
                        @SuppressWarnings("unchecked") List<Map<?, ?>> rawMediaItems = (List<Map<?, ?>>)response.get("children");
                        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
                        for (Map<?, ?> rawMediaItem : rawMediaItems) {
                            mediaItems.add(rawToMediaItem(rawMediaItem));
                        }
                        result.sendResult(mediaItems);
                    }
                });
            }
            result.detach();
        }

        @Override
        public void onLoadItem(String itemId, final MediaBrowserServiceCompat.Result<MediaBrowserCompat.MediaItem> result) {
            if (audioHandlerInterface != null) {
                Map<String, Object> args = new HashMap<>();
                args.put("mediaId", itemId);

                audioHandlerInterface.invokeMethod("getMediaItem", args, new MethodChannel.Result() {
                    @Override
                    public void error(String errorCode, String errorMessage, Object errorDetails) {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void notImplemented() {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void success(Object obj) {
                        Map<?, ?> response = (Map<?, ?>)obj;
                        Map<?, ?> rawMediaItem = (Map<?, ?>)response.get("mediaItem");
                        if (rawMediaItem != null) {
                            MediaBrowserCompat.MediaItem mediaItem = rawToMediaItem(rawMediaItem);
                            result.sendResult(mediaItem);
                        } else {
                            result.sendResult(null);
                        }
                    }
                });
            }
            result.detach();
        }

        @Override
        public void onSearch(String query, Bundle extras, final MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result) {
            if (audioHandlerInterface != null) {
                Map<String, Object> args = new HashMap<>();
                args.put("query", query);
                args.put("extras", bundleToMap(extras));
                audioHandlerInterface.invokeMethod("search", args, new MethodChannel.Result() {
                    @Override
                    public void error(String errorCode, String errorMessage, Object errorDetails) {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void notImplemented() {
                        result.sendError(new Bundle());
                    }

                    @Override
                    public void success(Object obj) {
                        Map<?, ?> response = (Map<?, ?>)obj;
                        @SuppressWarnings("unchecked") List<Map<?, ?>> rawMediaItems = (List<Map<?, ?>>)response.get("mediaItems");
                        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
                        for (Map<?, ?> rawMediaItem : rawMediaItems) {
                            mediaItems.add(rawToMediaItem(rawMediaItem));
                        }
                        result.sendResult(mediaItems);
                    }
                });
            }
            result.detach();
        }

        @Override
        public void onClick(MediaButton mediaButton) {
            invokeMethod("click", mapOf("button", mediaButton.ordinal()));
        }

        @Override
        public void onPause() {
            invokeMethod("pause", mapOf());
        }

        @Override
        public void onPrepare() {
            invokeMethod("prepare", mapOf());
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            invokeMethod("prepareFromMediaId", mapOf(
                        "mediaId", mediaId,
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            invokeMethod("prepareFromSearch", mapOf(
                        "query", query,
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPrepareFromUri(Uri uri, Bundle extras) {
            invokeMethod("prepareFromUri", mapOf(
                        "uri", uri.toString(),
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPlay() {
            invokeMethod("play", mapOf());
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            invokeMethod("playFromMediaId", mapOf(
                        "mediaId", mediaId,
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            invokeMethod("playFromSearch", mapOf(
                        "query", query,
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPlayFromUri(Uri uri, Bundle extras) {
            invokeMethod("playFromUri", mapOf(
                        "uri", uri.toString(),
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onPlayMediaItem(MediaMetadataCompat metadata) {
            invokeMethod("playMediaItem", mapOf("mediaItem", mediaMetadata2raw(metadata)));
        }

        @Override
        public void onStop() {
            invokeMethod("stop", mapOf());
        }

        @Override
        public void onAddQueueItem(MediaMetadataCompat metadata) {
            invokeMethod("addQueueItem", mapOf("mediaItem", mediaMetadata2raw(metadata)));
        }

        @Override
        public void onAddQueueItemAt(MediaMetadataCompat metadata, int index) {
            invokeMethod("insertQueueItem", mapOf(
                        "mediaItem", mediaMetadata2raw(metadata),
                        "index", index));
        }

        @Override
        public void onRemoveQueueItem(MediaMetadataCompat metadata) {
            invokeMethod("removeQueueItem", mapOf("mediaItem", mediaMetadata2raw(metadata)));
        }

        @Override
        public void onRemoveQueueItemAt(int index) {
            invokeMethod("removeQueueItemAt", mapOf("index", index));
        }

        @Override
        public void onSkipToQueueItem(long queueItemId) {
            invokeMethod("skipToQueueItem", mapOf("index", queueItemId));
        }

        @Override
        public void onSkipToNext() {
            invokeMethod("skipToNext", mapOf());
        }

        @Override
        public void onSkipToPrevious() {
            invokeMethod("skipToPrevious", mapOf());
        }

        @Override
        public void onFastForward() {
            invokeMethod("fastForward", mapOf());
        }

        @Override
        public void onRewind() {
            invokeMethod("rewind", mapOf());
        }

        @Override
        public void onSeekTo(long pos) {
            invokeMethod("seek", mapOf("position", pos*1000));
        }

        @Override
        public void onSetPlaybackSpeed(float speed) {
            invokeMethod("setSpeed", mapOf("speed", speed));
        }

        @Override
        public void onSetCaptioningEnabled(boolean enabled) {
            invokeMethod("setCaptioningEnabled", mapOf("enabled", enabled));
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            invokeMethod("setRepeatMode", mapOf("repeatMode", repeatMode));
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            invokeMethod("setShuffleMode", mapOf("shuffleMode", shuffleMode));
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            invokeMethod("customAction", mapOf(
                        "name", action,
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onSetRating(RatingCompat rating) {
            invokeMethod("setRating", mapOf(
                        "rating", rating2raw(rating),
                        "extras", null));
        }

        @Override
        public void onSetRating(RatingCompat rating, Bundle extras) {
            invokeMethod("setRating", mapOf(
                        "rating", rating2raw(rating),
                        "extras", bundleToMap(extras)));
        }

        @Override
        public void onSetVolumeTo(int volumeIndex) {
            invokeMethod("androidSetRemoteVolume", mapOf("volumeIndex", volumeIndex));
        }

        @Override
        public void onAdjustVolume(int direction) {
            invokeMethod("androidAdjustRemoteVolume", mapOf("direction", direction));
        }

        @Override
        public void onTaskRemoved() {
            invokeMethod("onTaskRemoved", mapOf());
        }

        @Override
        public void onClose() {
            invokeMethod("onNotificationDeleted", mapOf());
        }

        // The last values projected from the Dart AudioHandler. They are kept
        // on this object, which lives as long as the engine, so that an
        // AudioService instance created while the engine survived can be
        // brought back to the handler's current state (see onCreate).
        private Map<?, ?> lastStateArgs;
        private Map<?, ?> lastMediaItemArgs;
        private Map<?, ?> lastQueueArgs;
        private Map<?, ?> lastPlaybackInfoArgs;

        @Override
        public void onCreate() {
            // A new AudioService instance starts with an empty MediaSession
            // while the Dart handler's streams still hold the current state
            // and will not re-emit it. Re-project the last known values,
            // exactly once, through the same paths as the live updates, but
            // as a replay: no transition-specific side effects.
            if (lastStateArgs == null && lastMediaItemArgs == null
                    && lastQueueArgs == null && lastPlaybackInfoArgs == null) {
                return;
            }
            log("service_state_replay", "generation=" + flutterEngineGeneration
                    + " state=" + (lastStateArgs != null)
                    + " mediaItem=" + (lastMediaItemArgs != null)
                    + " queue=" + (lastQueueArgs != null)
                    + " playbackInfo=" + (lastPlaybackInfoArgs != null));
            try {
                if (lastPlaybackInfoArgs != null) {
                    applyPlaybackInfo(lastPlaybackInfoArgs);
                }
                if (lastStateArgs != null) {
                    applyState(lastStateArgs, true);
                }
            } catch (Exception e) {
                Log.e(AudioServiceLifecycleLog.TAG, "state replay failed: " + e.getMessage(), e);
            }
            if (lastMediaItemArgs != null) {
                applyMediaItem(lastMediaItemArgs, null);
            }
            if (lastQueueArgs != null) {
                applyQueue(lastQueueArgs, null);
            }
        }

        @Override
        public void onDestroy() {
            // The OS may stop the service while the handler still reports
            // playing, e.g. "Stopping service due to app idle" once the
            // service has lost its foreground state. The engine is disposed
            // regardless: keeping it alive left audio playing in a process
            // with no foreground service, media session or notification, where
            // a media button could restart the service without it reaching
            // startForeground(). Logged so these stops can be counted.
            final AudioService service = AudioService.instance;
            if (service != null && service.isPlaying()) {
                log("service_destroyed_while_playing", "generation=" + flutterEngineGeneration
                        + " serviceGeneration=" + service.getServiceGeneration()
                        + " processingState=" + service.getProcessingState());
            }
            // Deferred rather than inline: a service instance that the system
            // has already queued for creation must find the engine in the
            // cache (see scheduleFlutterEngineDisposal).
            scheduleFlutterEngineDisposal();
        }

        @Override
        public void onMethodCall(MethodCall call, Result result) {
            try {
                Map<?, ?> args = (Map<?, ?>)call.arguments;
                switch (call.method) {
                case "setMediaItem": {
                    lastMediaItemArgs = args;
                    applyMediaItem(args, result);
                    break;
                }
                case "setQueue": {
                    lastQueueArgs = args;
                    applyQueue(args, result);
                    break;
                }
                case "setState": {
                    lastStateArgs = args;
                    applyState(args, false);
                    if (AudioService.instance.consumeForegroundStartRefusal()) {
                        // The state was applied; only the foreground service
                        // is pending. Reported once per play, not per update.
                        result.error("FOREGROUND_START_REFUSED",
                                "Android refused to start AudioService in the foreground from the"
                                        + " background; it is retried when the Activity next resumes",
                                null);
                    } else {
                        result.success(null);
                    }
                    break;
                }
                case "setAndroidPlaybackInfo": {
                    lastPlaybackInfoArgs = args;
                    applyPlaybackInfo(args);
                    result.success(null);
                    break;
                }
                case "notifyChildrenChanged": {
                    String parentMediaId = (String)args.get("parentMediaId");
                    Map<?, ?> options = (Map<?, ?>)args.get("options");
                    AudioService.instance.notifyChildrenChanged(parentMediaId, mapToBundle(options));
                    result.success(null);
                    break;
                }
                case "androidForceEnableMediaButtons": {
                    // Just play a short amount of silence. This convinces Android
                    // that we are playing "real" audio so that it will route
                    // media buttons to us.
                    // See: https://issuetracker.google.com/issues/65344811
                    if (silenceAudioTrack == null) {
                        byte[] silence = new byte[2048];
                        // TODO: Uncomment this after moving to a minSdkVersion of 21.
                        /* AudioAttributes audioAttributes = new AudioAttributes.Builder() */
                        /*     .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) */
                        /*     .setUsage(AudioAttributes.USAGE_MEDIA) */
                        /*     .build(); */
                        /* AudioFormat audioFormat = new AudioFormat.Builder() */
                        /*     .setChannelMask(AudioFormat.CHANNEL_CONFIGURATION_MONO) */
                        /*     .setEncoding(AudioFormat.ENCODING_PCM_8BIT) */
                        /*     .setSampleRate(SILENCE_SAMPLE_RATE) */
                        /*     .build(); */
                        /* silenceAudioTrack = new AudioTrack.Builder() */
                        /*     .setAudioAttributes(audioAttributes) */
                        /*     .setAudioFormat(audioFormat) */
                        /*     .setBufferSizeInBytes(silence.length) */
                        /*     .setTransferMode(AudioTrack.MODE_STATIC) */
                        /*     .build(); */
                        @SuppressWarnings("deprecation")
                        final AudioTrack audioTrack = new AudioTrack(
                                AudioManager.STREAM_MUSIC,
                                SILENCE_SAMPLE_RATE,
                                AudioFormat.CHANNEL_CONFIGURATION_MONO,
                                AudioFormat.ENCODING_PCM_8BIT,
                                silence.length,
                                AudioTrack.MODE_STATIC);
                        silenceAudioTrack = audioTrack;
                        silenceAudioTrack.write(silence, 0, silence.length);
                    }
                    silenceAudioTrack.reloadStaticData();
                    silenceAudioTrack.play();
                    result.success(null);
                    break;
                }
                case "stopService": {
                    if (AudioService.instance != null) {
                        AudioService.instance.stop();
                    }
                    result.success(null);
                    break;
                }
                }
            } catch (Exception e) {
                e.printStackTrace();
                result.error(e.getMessage(), null, null);
            }
        }


        /** Projects a media item; {@code result} is null when replaying. */
        private void applyMediaItem(Map<?, ?> args, final Result result) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Map<?, ?> rawMediaItem = (Map<?, ?>)args.get("mediaItem");
                    MediaMetadataCompat mediaMetadata = createMediaMetadata(rawMediaItem);
                    AudioService.instance.setMetadata(mediaMetadata);
                    if (result != null) {
                        handler.post(() -> result.success(null));
                    }
                } catch (Exception e) {
                    if (result != null) {
                        handler.post(() -> {
                            result.error("UNEXPECTED_ERROR", "Unexpected error", Log.getStackTraceString(e));
                        });
                    } else {
                        Log.e(AudioServiceLifecycleLog.TAG, "media item replay failed: " + e.getMessage(), e);
                    }
                }
            });
        }

        /** Projects the queue; {@code result} is null when replaying. */
        private void applyQueue(Map<?, ?> args, final Result result) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    @SuppressWarnings("unchecked") List<Map<?, ?>> rawQueue = (List<Map<?, ?>>) args.get("queue");
                    List<MediaSessionCompat.QueueItem> queue = raw2queue(rawQueue);
                    AudioService.instance.setQueue(queue);
                    if (result != null) {
                        handler.post(() -> result.success(null));
                    }
                } catch (Exception e) {
                    if (result != null) {
                        handler.post(() -> {
                            result.error("UNEXPECTED_ERROR", "Unexpected error", Log.getStackTraceString(e));
                        });
                    } else {
                        Log.e(AudioServiceLifecycleLog.TAG, "queue replay failed: " + e.getMessage(), e);
                    }
                }
            });
        }

        /** Projects a playback state; see AudioService.setState for {@code replay}. */
        private void applyState(Map<?, ?> args, boolean replay) {
            Map<?, ?> stateMap = (Map<?, ?>)args.get("state");
            AudioProcessingState processingState = AudioProcessingState.values()[(Integer)stateMap.get("processingState")];
            boolean playing = (Boolean)stateMap.get("playing");
            @SuppressWarnings("unchecked") List<Map<?, ?>> rawControls = (List<Map<?, ?>>)stateMap.get("controls");
            @SuppressWarnings("unchecked") List<Object> compactActionIndexList = (List<Object>)stateMap.get("androidCompactActionIndices");
            @SuppressWarnings("unchecked") List<Integer> rawSystemActions = (List<Integer>)stateMap.get("systemActions");
            long position = getLong(stateMap.get("updatePosition"));
            long bufferedPosition = getLong(stateMap.get("bufferedPosition"));
            float speed = (float)((double)((Double)stateMap.get("speed")));
            long updateTimeSinceEpoch = stateMap.get("updateTime") == null ? System.currentTimeMillis() : getLong(stateMap.get("updateTime"));
            Integer errorCode = (Integer)stateMap.get("errorCode");
            String errorMessage = (String)stateMap.get("errorMessage");
            int repeatMode = (Integer)stateMap.get("repeatMode");
            int shuffleMode = (Integer)stateMap.get("shuffleMode");
            Long queueIndex = getLong(stateMap.get("queueIndex"));
            boolean captioningEnabled = (Boolean)stateMap.get("captioningEnabled");

            // On the flutter side, we represent the update time relative to the epoch.
            // On the native side, we must represent the update time relative to the boot time.
            long updateTimeSinceBoot = updateTimeSinceEpoch - bootTime;

            List<MediaControl> actions = new ArrayList<>();
            long actionBits = 0;
            for (Map<?, ?> rawControl : rawControls) {
                String resource = (String)rawControl.get("androidIcon");
                String label = (String)rawControl.get("label");
                long actionCode = 1 << ((Integer)rawControl.get("action"));
                actionBits |= actionCode;
                Map<?, ?> customActionMap = (Map<?, ?>)rawControl.get("customAction");
                CustomMediaAction customAction = null;
                if (customActionMap != null) {
                    String name = (String) customActionMap.get("name");
                    Map<?, ?> extras = (Map<?, ?>) customActionMap.get("extras");
                    customAction = new CustomMediaAction(name, extras);
                }
                actions.add(new MediaControl(resource, label, actionCode, customAction));
            }
            for (Integer rawSystemAction : rawSystemActions) {
                long actionCode = 1 << rawSystemAction;
                actionBits |= actionCode;
            }
            int[] compactActionIndices = null;
            if (compactActionIndexList != null) {
                compactActionIndices = new int[Math.min(AudioService.MAX_COMPACT_ACTIONS, compactActionIndexList.size())];
                for (int i = 0; i < compactActionIndices.length; i++)
                    compactActionIndices[i] = (Integer)compactActionIndexList.get(i);
            }
            AudioService.instance.setState(
                    actions,
                    actionBits,
                    compactActionIndices,
                    processingState,
                    playing,
                    position,
                    bufferedPosition,
                    speed,
                    updateTimeSinceBoot,
                    errorCode,
                    errorMessage,
                    repeatMode,
                    shuffleMode,
                    captioningEnabled,
                    queueIndex,
                    replay);
        }

        /** Projects the Android playback info (local/remote volume handling). */
        private void applyPlaybackInfo(Map<?, ?> args) {
            Map<?, ?> playbackInfo = (Map<?, ?>)args.get("playbackInfo");
            final int playbackType = (Integer)playbackInfo.get("playbackType");
            final Integer volumeControlType = (Integer)playbackInfo.get("volumeControlType");
            final Integer maxVolume = (Integer)playbackInfo.get("maxVolume");
            final Integer volume = (Integer)playbackInfo.get("volume");
            AudioService.instance.setPlaybackInfo(playbackType, volumeControlType, maxVolume, volume);
        }

        @UiThread
        public void invokeMethod(String method, Object arg) {
            invokeMethod(method, arg, null);
        }

        @UiThread
        public void invokeMethod(String method, Object arg, final Result result) {
            log("handler_invoke", "generation=" + flutterEngineGeneration
                    + " method=" + method + " flutterReady=" + flutterReady
                    + " serviceGeneration=" + serviceGenerationOrNone());
            final Result loggedResult = new LoggedResult(method, result);
            if (flutterReady) {
                channel.invokeMethod(method, arg, loggedResult);
            } else {
                methodInvocationQueue.add(new MethodInvocation(method, arg, loggedResult));
            }
        }

        private void destroy() {
            if (silenceAudioTrack != null)
                silenceAudioTrack.release();
        }
    }

    /** Logs how Dart answered a handler invocation, then forwards the answer. */
    private static class LoggedResult implements Result {
        private final String method;
        private final Result delegate;

        LoggedResult(String method, Result delegate) {
            this.method = method;
            this.delegate = delegate;
        }

        @Override
        public void success(Object value) {
            log("handler_result", "generation=" + flutterEngineGeneration
                    + " method=" + method + " outcome=success");
            if (delegate != null) delegate.success(value);
        }

        @Override
        public void error(String errorCode, String errorMessage, Object errorDetails) {
            log("handler_result", "generation=" + flutterEngineGeneration
                    + " method=" + method + " outcome=error code=" + errorCode
                    + " message=" + errorMessage);
            if (delegate != null) delegate.error(errorCode, errorMessage, errorDetails);
        }

        @Override
        public void notImplemented() {
            log("handler_result", "generation=" + flutterEngineGeneration
                    + " method=" + method + " outcome=not_implemented");
            if (delegate != null) delegate.notImplemented();
        }
    }

    private static List<Map<?, ?>> mediaItems2raw(List<MediaBrowserCompat.MediaItem> mediaItems) {
        List<Map<?, ?>> rawMediaItems = new ArrayList<>();
        for (MediaBrowserCompat.MediaItem mediaItem : mediaItems) {
            MediaDescriptionCompat description = mediaItem.getDescription();
            MediaMetadataCompat mediaMetadata = AudioService.getMediaMetadata(description.getMediaId());
            rawMediaItems.add(mediaMetadata2raw(mediaMetadata));
        }
        return rawMediaItems;
    }

    private static List<Map<?, ?>> queue2raw(List<MediaSessionCompat.QueueItem> queue) {
        if (queue == null) return null;
        List<Map<?, ?>> rawQueue = new ArrayList<>();
        for (MediaSessionCompat.QueueItem queueItem : queue) {
            MediaDescriptionCompat description = queueItem.getDescription();
            MediaMetadataCompat mediaMetadata = AudioService.getMediaMetadata(description.getMediaId());
            rawQueue.add(mediaMetadata2raw(mediaMetadata));
        }
        return rawQueue;
    }

    private static RatingCompat raw2rating(Map<?, ?> raw) {
        if (raw == null) return null;
        Integer type = (Integer)raw.get("type");
        Object value = raw.get("value");
        if (value != null) {
            switch (type) {
            case RatingCompat.RATING_3_STARS:
            case RatingCompat.RATING_4_STARS:
            case RatingCompat.RATING_5_STARS:
                return RatingCompat.newStarRating(type, (int)value);
            case RatingCompat.RATING_HEART:
                return RatingCompat.newHeartRating((boolean)value);
            case RatingCompat.RATING_PERCENTAGE:
                return RatingCompat.newPercentageRating(((Double)value).floatValue());
            case RatingCompat.RATING_THUMB_UP_DOWN:
                return RatingCompat.newThumbRating((boolean)value);
            default:
                return RatingCompat.newUnratedRating(type);
            }
        } else {
            return RatingCompat.newUnratedRating(type);
        }
    }

    private static HashMap<String, Object> rating2raw(RatingCompat rating) {
        HashMap<String, Object> raw = new HashMap<>();
        raw.put("type", rating.getRatingStyle());
        if (rating.isRated()) {
            switch (rating.getRatingStyle()) {
            case RatingCompat.RATING_3_STARS:
            case RatingCompat.RATING_4_STARS:
            case RatingCompat.RATING_5_STARS:
                raw.put("value", rating.getStarRating());
                break;
            case RatingCompat.RATING_HEART:
                raw.put("value", rating.hasHeart());
                break;
            case RatingCompat.RATING_PERCENTAGE:
                raw.put("value", rating.getPercentRating());
                break;
            case RatingCompat.RATING_THUMB_UP_DOWN:
                raw.put("value", rating.isThumbUp());
                break;
            case RatingCompat.RATING_NONE:
                raw.put("value", null);
            }
        } else {
            raw.put("value", null);
        }
        return raw;
    }

    private static String metadataToString(MediaMetadataCompat mediaMetadata, String key) {
        CharSequence value = mediaMetadata.getText(key);
        if (value != null)
            return value.toString();
        return null;
    }

    private static Map<?, ?> mediaMetadata2raw(MediaMetadataCompat mediaMetadata) {
        if (mediaMetadata == null) return null;
        MediaDescriptionCompat description = mediaMetadata.getDescription();
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", description.getMediaId());
        raw.put("title", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_TITLE));
        raw.put("album", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_ALBUM));
        if (description.getIconUri() != null)
            raw.put("artUri", description.getIconUri().toString());
        raw.put("artist", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_ARTIST));
        raw.put("genre", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_GENRE));
        if (mediaMetadata.containsKey(MediaMetadataCompat.METADATA_KEY_DURATION))
            raw.put("duration", mediaMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));
        raw.put("playable", mediaMetadata.getLong("playable_long") != 0);
        raw.put("displayTitle", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE));
        raw.put("displaySubtitle", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE));
        raw.put("displayDescription", metadataToString(mediaMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION));
        if (mediaMetadata.containsKey(MediaMetadataCompat.METADATA_KEY_RATING)) {
            raw.put("rating", rating2raw(mediaMetadata.getRating(MediaMetadataCompat.METADATA_KEY_RATING)));
        }
        Map<String, Object> extras = bundleToMap(mediaMetadata.getBundle());
        if (extras.size() > 0) {
            raw.put("extras", extras);
        }
        return raw;
    }

    private static MediaMetadataCompat createMediaMetadata(Map<?, ?> rawMediaItem) {
       //noinspection unchecked
       return AudioService.instance.createMediaMetadata(
                (String)rawMediaItem.get("id"),
                (String)rawMediaItem.get("title"),
                (String)rawMediaItem.get("album"),
                (String)rawMediaItem.get("artist"),
                (String)rawMediaItem.get("genre"),
                getLong(rawMediaItem.get("duration")),
                (String)rawMediaItem.get("artUri"),
                (Boolean)rawMediaItem.get("playable"),
                (String)rawMediaItem.get("displayTitle"),
                (String)rawMediaItem.get("displaySubtitle"),
                (String)rawMediaItem.get("displayDescription"),
                raw2rating((Map<?, ?>)rawMediaItem.get("rating")),
                (Map<?, ?>)rawMediaItem.get("extras")
        );
    }

    /**
     * Propagate mediaItem extras passed from dart to the description. By default, when creating
     * a MediaMetadataCompat object, it doesn't propagate all the extras to the MediaDescription
     * instance it holds.
     *
     * @param description original description object
     * @param extras extras map coming from dart
     * @return description with added extras
     */
    private static MediaDescriptionCompat addExtrasToMediaDescription(MediaDescriptionCompat description, Map<?, ?> extras) {
        if (extras == null || extras.isEmpty()) {
            return description;
        }
        final Bundle extrasBundle = new Bundle();
        if (description.getExtras() != null) {
            extrasBundle.putAll(description.getExtras());
        }
        extrasBundle.putAll(mapToBundle(extras));
        return new MediaDescriptionCompat.Builder()
                .setTitle(description.getTitle())
                .setSubtitle(description.getSubtitle())
                .setDescription(description.getDescription())
                .setIconBitmap(description.getIconBitmap())
                .setIconUri(description.getIconUri())
                .setMediaId(description.getMediaId())
                .setMediaUri(description.getMediaUri())
                .setExtras(extrasBundle).build();
    }

    private static MediaBrowserCompat.MediaItem rawToMediaItem(Map<?, ?> rawMediaItem) {
        MediaMetadataCompat mediaMetadata = createMediaMetadata(rawMediaItem);
        final MediaDescriptionCompat description = addExtrasToMediaDescription(mediaMetadata.getDescription(), (Map<?, ?>)rawMediaItem.get("extras"));
        final Boolean playable = (Boolean)rawMediaItem.get("playable");
        return new MediaBrowserCompat.MediaItem(description, playable ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private static List<MediaSessionCompat.QueueItem> raw2queue(List<Map<?, ?>> rawQueue) {
        List<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> rawMediaItem : rawQueue) {
            MediaMetadataCompat mediaMetadata = createMediaMetadata(rawMediaItem);
            MediaDescriptionCompat description = addExtrasToMediaDescription(mediaMetadata.getDescription(), (Map<?, ?>)rawMediaItem.get("extras"));
            queue.add(new MediaSessionCompat.QueueItem(description, i));
            i++;
        }
        return queue;
    }

    public static Long getLong(Object o) {
        return (o == null || o instanceof Long) ? (Long)o : Long.valueOf((Integer) o);
    }

    public static Integer getInt(Object o) {
        return (o == null || o instanceof Integer) ? (Integer)o : Integer.valueOf((int)((Long)o).longValue());
    }

    static Map<String, Object> bundleToMap(Bundle bundle) {
        if (bundle == null) return null;
        Map<String, Object> map = new HashMap<>();
        for (String key : bundle.keySet()) {
            // TODO: use typesafe version once SDK 33 is released.
            @SuppressWarnings("deprecation")
            Object value = bundle.getSerializable(key);
            if (value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    static Bundle mapToBundle(Map<?, ?> map) {
        if (map == null) return null;
        final Bundle bundle = new Bundle();
        for (Object key : map.keySet()) {
            String skey = (String)key;
            Object value = map.get(skey);
            if (value instanceof Integer) bundle.putInt(skey, (Integer)value);
            else if (value instanceof Long) bundle.putLong(skey, (Long)value);
            else if (value instanceof Double) bundle.putDouble(skey, (Double)value);
            else if (value instanceof Boolean) bundle.putBoolean(skey, (Boolean)value);
            else if (value instanceof String) bundle.putString(skey, (String)value);
        }
        return bundle;
    }

    static Map<String, Object> mapOf(Object... args) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            map.put((String)args[i], args[i + 1]);
        }
        return map;
    }

    static class MethodInvocation {
        public final String method;
        public final Object arg;
        public final Result result;

        public MethodInvocation(String method, Object arg, Result result) {
            this.method = method;
            this.arg = arg;
            this.result = result;
        }
    }
}
