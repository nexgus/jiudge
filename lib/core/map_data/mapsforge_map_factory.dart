import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:mapsforge_flutter/mapsforge.dart';
import 'package:mapsforge_flutter_core/model.dart';
import 'package:mapsforge_flutter_mapfile/mapfile.dart';
import 'package:mapsforge_flutter_renderer/cache.dart';
import 'package:mapsforge_flutter_renderer/offline_renderer.dart';
import 'package:mapsforge_flutter_rendertheme/rendertheme.dart';

import 'theme_sanitizer.dart';

/// Builds a [MapModel] from RudyMap data sitting in a local directory.
///
/// Phase 0 prototype only: paths are read straight from the filesystem. The
/// download/unzip/import lifecycle (spec §2.3) is Phase 1 work and lives in
/// [MapDataRepository]; this factory just renders whatever is already on disk.
///
/// The directory is expected to contain the unzipped RudyMap full bundle:
///   `<dir>/MOI_OSM_Taiwan_TOPO_Rudy.map`   the mapsforge basemap
///   `<dir>/MOI_OSM.xml`                     the bundled render theme
///   `<dir>/moiosmhs_res/...`                theme symbol resources (SVG/PNG)
class MapsforgeMapFactory {
  const MapsforgeMapFactory._();

  static const String basemapName = 'MOI_OSM_Taiwan_TOPO_Rudy.map';
  static const String themeName = 'MOI_OSM.xml';

  /// Loads the basemap + theme from [dataDir] and returns a ready [MapModel].
  ///
  /// The caller owns the returned model and must call [MapModel.dispose] on it.
  ///
  /// [preferredLanguage] selects the label language when the `.map` carries
  /// multilingual `name:<lang>` tags (Phase 0 unknown: does RudyMap ship
  /// `name:en`?). Pass `'en'` to probe English labels, `null` for the default.
  static Future<MapModel> createFromLocalData({
    required String dataDir,
    String? preferredLanguage,
  }) async {
    // RudyMap's theme references its symbols as src="file:moiosmhs_res/...".
    // The default symbol cache only understands the "jar:" (asset-bundle)
    // prefix, so register a filesystem loader for the "file:" prefix. The cache
    // strips the prefix before calling the loader, leaving "moiosmhs_res/x.svg"
    // to be resolved against dataDir.
    SymbolCacheMgr().addLoader(
      'file:',
      ImageFileLoader(pathPrefix: '$dataDir/'),
    );

    final Datastore datastore = await Mapfile.createFromFile(
      filename: '$dataDir/$basemapName',
      preferredLanguage: preferredLanguage,
    );
    // In debug (JIT, asserts on) RudyMap's theme trips an over-strict assert in
    // the rendertheme parser; sanitize it first. Release (AOT) strips the
    // assert, so the theme is consumed verbatim. See [ThemeSanitizer].
    String themeXml = await File('$dataDir/$themeName').readAsString();
    if (kDebugMode) {
      themeXml = ThemeSanitizer.stripBuildFailingTopRules(themeXml);
    }
    final Rendertheme rendertheme = RenderThemeBuilder.createFromString(
      themeXml,
    );
    final DatastoreRenderer renderer = DatastoreRenderer(
      datastore,
      rendertheme,
    );

    // RudyMap's `.map` tops out around zoom 21; cap to avoid empty tiles.
    return MapModel(
      renderer: renderer,
      zoomlevelRange: const ZoomlevelRange(0, 21),
    );
  }
}
