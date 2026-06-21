/// A recorded GPS track.
///
/// TODO(spec §1.1 F4/F5): finalize fields (samples, cached stats) as recording
/// lands.
class Track {
  const Track({
    required this.id,
    required this.name,
    required this.points,
    required this.startedAt,
    this.finishedAt,
  });

  final String id;
  final String name;
  final List<TrackPoint> points;
  final DateTime startedAt;
  final DateTime? finishedAt;
}

/// A single recorded point with elevation and timestamp.
class TrackPoint {
  const TrackPoint({
    required this.latitude,
    required this.longitude,
    required this.elevation,
    required this.time,
  });

  final double latitude;
  final double longitude;
  final double elevation;
  final DateTime time;
}
