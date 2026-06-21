/// Manages the on-device RudyMap data lifecycle.
///
/// Responsibilities: check for updates (HTTP HEAD vs the stored Last-Modified /
/// ETag), download a zip, unzip, integrity-check (open-ability; no official
/// checksum exists), then atomic-swap into place. Also supports manual local
/// import as an offline fallback.
/// TODO(spec §2.3, §4.3, F8): implement download -> unzip -> verify -> swap.
abstract interface class MapDataRepository {
  /// Whether a newer version is available on any mirror.
  Future<bool> isUpdateAvailable();

  /// Downloads and activates the latest data for the given [items].
  Future<void> update(Set<MapDataItem> items);

  /// Imports user-supplied files from a local [path] (offline fallback).
  Future<void> importFromLocal(String path);
}

/// The independently-updatable data components.
enum MapDataItem { basemap, dem, style }
