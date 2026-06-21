import 'waypoint.dart';

/// A planned hiking route through an ordered list of waypoints.
///
/// Named `HikingRoute` to avoid clashing with Flutter's `Route`.
/// TODO(spec §1.1 F2/F3): add geometry + length / ascent / descent stats.
class HikingRoute {
  const HikingRoute({
    required this.id,
    required this.name,
    required this.waypoints,
  });

  final String id;
  final String name;
  final List<Waypoint> waypoints;
}
