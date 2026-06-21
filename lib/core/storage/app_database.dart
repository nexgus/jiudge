/// SQLite-backed storage for tracks, routes, and settings.
///
/// TODO(spec §2.1): implement with a SQLite package (to be chosen - ask before
/// adding the dependency, per CLAUDE.md).
abstract interface class AppDatabase {
  Future<void> open();
  Future<void> close();
}
