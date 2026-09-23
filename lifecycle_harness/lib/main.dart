import 'dart:convert';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/widgets.dart';

/// Minimal app for the AudioService lifecycle instrumentation tests.
///
/// It performs the same startup as a typical client (AudioService.init before
/// runApp) with a handler that renders no audio, so the native lifecycle is
/// driven only by Activity attach/detach, MediaBrowser bindings and the states
/// the handler publishes. The handler publishes one media item (with local
/// artwork) and a queue so that ServiceRecreationTest can check that state is
/// replayed into a recreated AudioService. Its play() and pause() only publish
/// a playing or paused state, which puts AudioService in (or takes it out of)
/// its playing foreground state as real playback would.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AudioService.init(
    builder: () => _HarnessAudioHandler(),
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

/// A valid 1x1 PNG. A file:// artUri is sent to the platform as its cache
/// file path, so artwork loads without network access.
const _artworkPng =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

class _HarnessAudioHandler extends BaseAudioHandler {
  _HarnessAudioHandler() {
    _publish();
  }

  @override
  Future<void> play() async {
    playbackState.add(playbackState.value.copyWith(
      playing: true,
      processingState: AudioProcessingState.ready,
    ));
  }

  @override
  Future<void> pause() async {
    playbackState.add(playbackState.value.copyWith(playing: false));
  }

  Future<void> _publish() async {
    final art = File('${Directory.systemTemp.path}/harness_art.png');
    await art.writeAsBytes(base64Decode(_artworkPng), flush: true);
    final item = MediaItem(
      id: 'harness-item',
      title: 'Harness item',
      album: 'AudioService lifecycle harness',
      duration: const Duration(minutes: 1),
      artUri: art.uri,
    );
    queue.add([item]);
    mediaItem.add(item);
  }
}
