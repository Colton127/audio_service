import 'dart:convert';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/services.dart';
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
/// its playing foreground state as real playback would, and seek() publishes
/// a new position (also as the buffered position) without leaving that state. Errors reaching
/// AudioService.asyncError are counted in the media item's extras, where the
/// instrumentation tests read them through a MediaController.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final handler = await AudioService.init(
    builder: () => _HarnessAudioHandler(),
    config: const AudioServiceConfig(
      androidNotificationChannelId:
          'com.ryanheise.audio_service_lifecycle_harness.channel.audio',
      androidNotificationChannelName: 'Audio playback',
    ),
  );
  AudioService.asyncError.listen(handler.reportAsyncError);
  runApp(const Directionality(
    textDirection: TextDirection.ltr,
    child: Center(child: Text('AudioService lifecycle harness')),
  ));
}

/// A valid 64x64 PNG. A file:// artUri is sent to the platform as its cache
/// file path, so artwork loads without network access. Android 8.0 cannot lay
/// out a media notification with 1x1 artwork ("Couldn't inflate contentViews"),
/// which kills the app.
const _artworkPng =
    'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAT0lEQVR42u3PQQkAAAgEsItjJtMZ1Qi+hcEKLNXzWgQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQELgtQSiEPtYVDTgAAAABJRU5ErkJggg==';

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

  /// Also publishes [position] as the buffered position: Android extrapolates
  /// the position of a playing state for its controllers, but not the
  /// buffered position, so tests wait for that to know a seek was applied.
  @override
  Future<void> seek(Duration position) async {
    playbackState.add(playbackState.value
        .copyWith(updatePosition: position, bufferedPosition: position));
  }

  /// The children of [slowParentMediaId] are answered after five seconds, so
  /// that a test can destroy AudioService while the request is pending.
  static const slowParentMediaId = 'slow';

  /// Asking for the children of [failingParentMediaId] throws.
  static const failingParentMediaId = 'failing';

  @override
  Future<List<MediaItem>> getChildren(String parentMediaId,
      [Map<String, dynamic>? options]) async {
    switch (parentMediaId) {
      case slowParentMediaId:
        await Future<void>.delayed(const Duration(seconds: 5));
        return queue.value;
      case failingParentMediaId:
        throw StateError('simulated getChildren failure');
      default:
        return [];
    }
  }

  var _asyncErrorCount = 0;

  void reportAsyncError(Object error) {
    // A failed media item update is reported with this code; counting it
    // would send another media item update, which could fail the same way.
    if (error is PlatformException && error.code == 'UNEXPECTED_ERROR') return;
    final item = mediaItem.value;
    if (item == null) return;
    _asyncErrorCount++;
    mediaItem.add(item.copyWith(extras: {
      ...?item.extras,
      'asyncErrorCount': _asyncErrorCount,
      'lastAsyncError': error is PlatformException ? error.code : '$error',
    }));
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
