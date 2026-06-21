@Tags(['probe'])
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mapsforge_flutter_mapfile/mapfile.dart';

/// Phase 0 headless probe (spec §6): opens the RudyMap `.map` straight off disk
/// and dumps its header so we can answer the open questions without a device:
/// which label languages it declares (the `name:en` question), the available
/// zoom range, bounding box, and the global POI/way tag pool.
///
/// Not a CI test - it depends on local data. Run explicitly:
///   flutter test test/mapfile_probe_test.dart \
///     --dart-define=RUDYMAP_DIR=/Users/scgus/rudymap-data
void main() {
  const String dataDir = String.fromEnvironment(
    'RUDYMAP_DIR',
    defaultValue: '/Users/scgus/rudymap-data',
  );
  final String mapPath = '$dataDir/MOI_OSM_Taiwan_TOPO_Rudy.map';

  test('probe RudyMap .map header', () async {
    if (!File(mapPath).existsSync()) {
      markTestSkipped(
        'no RudyMap data at $mapPath - set --dart-define=RUDYMAP_DIR',
      );
      return;
    }

    final mapfile = await Mapfile.createFromFile(filename: mapPath);
    // The header is read lazily on first access; force it before reading getters.
    await mapfile.getBoundingBox();
    final header = mapfile.getMapHeaderInfo();

    stderr.writeln('=== RudyMap .map header ===');
    stderr.writeln(header.toString());
    stderr.writeln('--- languages declared: ${mapfile.getMapLanguages()}');
    stderr.writeln(
      '--- zoom range: ${mapfile.getMapFileInfo().zoomlevelRange}',
    );
    stderr.writeln('--- bbox: ${header.boundingBox}');
    stderr.writeln(
      '--- start: ${header.startPosition} z${header.startZoomLevel}',
    );
    stderr.writeln(
      '--- mapDate(ms): ${header.mapDate}  createdBy: ${header.createdBy}',
    );

    // Scan the global tag pool for any name:<lang> keys as a second signal.
    final langTags = <String>{
      for (final t in header.poiTags) t.key ?? '',
      for (final t in header.wayTags) t.key ?? '',
    }.where((k) => k.startsWith('name:')).toList()..sort();
    stderr.writeln('--- name:* tag keys in pool: $langTags');

    mapfile.dispose();
  }, tags: ['probe']);
}
