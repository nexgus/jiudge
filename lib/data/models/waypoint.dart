/// A planning waypoint the route must pass through.
class Waypoint {
  const Waypoint({required this.latitude, required this.longitude, this.name});

  final double latitude;
  final double longitude;
  final String? name;
}
