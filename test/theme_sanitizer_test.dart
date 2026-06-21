@Tags(['probe'])
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jiudge/core/map_data/theme_sanitizer.dart';
import 'package:mapsforge_flutter_rendertheme/rendertheme.dart';

/// Guards the debug-only theme workaround (see [ThemeSanitizer]): RudyMap's
/// theme trips the rendertheme parser's empty-rule assertion in debug, and the
/// sanitizer must make it build. Depends on local RudyMap data, so it skips
/// when the theme is absent rather than failing in CI.
void main() {
  const String dataDir = String.fromEnvironment(
    'RUDYMAP_DIR',
    defaultValue: '/Users/scgus/rudymap-data',
  );
  final String themePath = '$dataDir/MOI_OSM.xml';

  test('sanitized RudyMap theme builds; raw theme asserts (debug)', () {
    if (!File(themePath).existsSync()) {
      markTestSkipped('no theme at $themePath - set --dart-define=RUDYMAP_DIR');
      return;
    }
    final String raw = File(themePath).readAsStringSync();

    // Raw theme trips the parser's empty-rule assertion in debug (asserts on).
    expect(
      () => RenderThemeBuilder.createFromString(raw),
      throwsA(isA<AssertionError>()),
    );

    // Sanitized theme builds cleanly.
    final String sanitized = ThemeSanitizer.stripBuildFailingTopRules(raw);
    expect(
      () => RenderThemeBuilder.createFromString(sanitized),
      returnsNormally,
    );
  }, tags: ['probe']);
}
