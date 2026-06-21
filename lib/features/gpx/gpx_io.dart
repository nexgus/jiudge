import '../../data/models/route.dart';
import '../../data/models/track.dart';

/// GPX/KML import and export, compatible with mainstream hiking apps.
///
/// TODO(spec §1.1 F7): implement parse / serialize + round-trip tests.
abstract interface class GpxIo {
  /// Parses tracks/routes/waypoints from GPX or KML [data].
  Future<GpxDocument> parse(String data);

  /// Serializes [document] to GPX.
  Future<String> toGpx(GpxDocument document);
}

/// Parsed GPX/KML contents.
class GpxDocument {
  const GpxDocument({
    this.tracks = const <Track>[],
    this.routes = const <HikingRoute>[],
  });

  final List<Track> tracks;
  final List<HikingRoute> routes;
}
