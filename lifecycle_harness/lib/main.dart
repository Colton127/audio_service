import 'package:audio_service/audio_service.dart';
import 'package:flutter/widgets.dart';

/// Minimal app for the AudioService lifecycle instrumentation tests.
///
/// It performs the same startup as a typical client (AudioService.init before
/// runApp) with a handler that never plays, so the native lifecycle is driven
/// only by Activity attach/detach and the MediaBrowser binding.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AudioService.init(
    builder: () => _IdleAudioHandler(),
    config: const AudioServiceConfig(
      androidNotificationChannelId:
          'com.ryanheise.audio_service_lifecycle_harness.channel.audio',
      androidNotificationChannelName: 'Audio playback',
    ),
  );
  runApp(const Directionality(
    textDirection: TextDirection.ltr,
    child: Center(child: Text('AudioService lifecycle harness')),
  ));
}

class _IdleAudioHandler extends BaseAudioHandler {}
