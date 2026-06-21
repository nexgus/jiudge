/// Background-capable location source.
///
/// Implemented natively (Android Foreground Service + Wake Lock) behind a
/// platform channel; see `MethodChannels.location`. Kept abstract so Dart code
/// stays decoupled from the native implementation.
/// TODO(spec §2.1, F4): implement the foreground-service-backed fix stream.
abstract interface class LocationService {
  /// A stream of position fixes while recording is active.
  Stream<LocationFix> get fixes;

  /// Starts the foreground service and begins emitting fixes.
  Future<void> start();

  /// Stops the foreground service.
  Future<void> stop();
}

/// A single GPS fix.
class LocationFix {
  const LocationFix({
    required this.latitude,
    required this.longitude,
    required this.altitude,
    required this.accuracy,
    required this.timestamp,
  });

  final double latitude;
  final double longitude;
  final double altitude;
  final double accuracy;
  final DateTime timestamp;
}
