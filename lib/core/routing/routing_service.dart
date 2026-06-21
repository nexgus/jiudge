import '../../data/models/route.dart';
import '../../data/models/waypoint.dart';

/// Offline routing over the GraphHopper engine (embedded in Android, Java),
/// reached via `MethodChannels.routing`.
///
/// TODO(spec §2.1, F2, Phase 2): wrap the GraphHopper hiking profile.
abstract interface class RoutingService {
  /// Computes a hiking route through the given ordered [waypoints].
  Future<HikingRoute> route(List<Waypoint> waypoints);
}
