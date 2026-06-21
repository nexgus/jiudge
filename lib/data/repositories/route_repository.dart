import '../models/route.dart';

/// Persists and retrieves planned routes.
///
/// TODO(spec §2.1): back with AppDatabase (SQLite).
abstract interface class RouteRepository {
  Future<List<HikingRoute>> listAll();
  Future<void> save(HikingRoute route);
  Future<void> delete(String id);
}
