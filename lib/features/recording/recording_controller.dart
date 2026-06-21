import 'package:flutter/foundation.dart';

/// Drives a recording session and exposes live stats (distance, ascent, time,
/// speed). Configuration changes (rotation) must not interrupt recording.
///
/// TODO(spec §1.1 F4/F5): connect to the foreground LocationService stream.
class RecordingController extends ChangeNotifier {
  bool _isRecording = false;

  bool get isRecording => _isRecording;

  void start() {
    if (_isRecording) return;
    _isRecording = true;
    notifyListeners();
  }

  void stop() {
    if (!_isRecording) return;
    _isRecording = false;
    notifyListeners();
  }
}
