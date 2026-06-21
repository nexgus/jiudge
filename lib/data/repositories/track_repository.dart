import '../models/track.dart';

/// Persists and retrieves recorded tracks.
///
/// TODO(spec §2.1): back with AppDatabase (SQLite).
abstract interface class TrackRepository {
  Future<List<Track>> listAll();
  Future<void> save(Track track);
  Future<void> delete(String id);
}
