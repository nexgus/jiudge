import 'package:flutter/material.dart';
import 'package:mapsforge_flutter/mapsforge.dart';
import 'package:mapsforge_flutter/marker.dart';
import 'package:mapsforge_flutter/overlay.dart';
import 'package:mapsforge_flutter_core/model.dart';
import 'package:mapsforge_flutter_core/utils.dart';

import '../../core/map_data/mapsforge_map_factory.dart';

/// Map view: renders a RudyMap `.map` via `mapsforge_flutter`.
///
/// Phase 0 prototype (spec §6): proves the renderer loads the RudyMap basemap +
/// bundled theme, draws CJK labels, honours pinch/rotate gestures, and lets us
/// probe the `name:en` label question via the language toggle in the app bar.
/// A fake GPS marker stands in for the real location feed (Phase 1).
///
/// The data directory is supplied at build time:
///   flutter run --dart-define=RUDYMAP_DIR=/abs/path/to/unzipped/rudymap
/// On a device, push the unzipped bundle and point this at that path.
class MapScreen extends StatefulWidget {
  const MapScreen({super.key});

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  /// Directory holding the unzipped RudyMap bundle (.map + theme + symbols).
  /// Defaults to the desktop dev location; override with --dart-define.
  static const String _dataDir = String.fromEnvironment(
    'RUDYMAP_DIR',
    defaultValue: '/Users/scgus/rudymap-data',
  );

  /// Initial camera: Guanyinshan main peak (Yinghanling), Bali - 616 m.
  static const double _initialLat = 25.1363861;
  static const double _initialLng = 121.4275306;
  static const int _initialZoom = 15;

  /// Marker datastore for the fake GPS dot.
  final DefaultMarkerDatastore<String> _markers =
      DefaultMarkerDatastore<String>();

  Future<MapModel>? _modelFuture;
  MapModel? _mapModel;

  /// Label language: null = `.map` default, 'en' = probe `name:en`.
  String? _language;
  bool _scaleSet = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_scaleSet) {
      _scaleSet = true;
      // Crisper tiles on HiDPI screens; must be set before the theme is parsed.
      MapsforgeSettingsMgr().setDeviceScaleFactor(
        MediaQuery.devicePixelRatioOf(context),
      );
      _modelFuture = _build();
    }
  }

  Future<MapModel> _build() async {
    final MapModel model = await MapsforgeMapFactory.createFromLocalData(
      dataDir: _dataDir,
      preferredLanguage: _language,
    );
    model.setPosition(MapPosition(_initialLat, _initialLng, _initialZoom));

    _markers.clearMarkers();
    _markers.addMarker(
      PoiMarker<String>(
        latLong: const LatLong(_initialLat, _initialLng),
        src: 'file:moiosmhs_res/s_gpx_red_pin.png',
        width: 32,
        height: 32,
      ),
    );

    _mapModel = model;
    return model;
  }

  void _toggleLanguage() {
    setState(() {
      _language = _language == null ? 'en' : null;
      _mapModel?.dispose();
      _mapModel = null;
      _modelFuture = _build();
    });
  }

  @override
  void dispose() {
    _mapModel?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Jiudge - RudyMap (${_language ?? 'default'})'),
        actions: [
          IconButton(
            tooltip: 'Toggle label language (default / en)',
            icon: const Icon(Icons.translate),
            onPressed: _toggleLanguage,
          ),
        ],
      ),
      body: FutureBuilder<MapModel>(
        future: _modelFuture,
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return ErrorhelperWidget(
              error: snapshot.error!,
              stackTrace: snapshot.stackTrace,
            );
          }
          final MapModel? model = snapshot.data;
          if (model == null) {
            return const Center(child: CircularProgressIndicator());
          }
          return Stack(
            children: [
              // Default overlays minus IndoorlevelOverlay: the indoor floor
              // bar (UG/EG/OG) is meaningless for an outdoor hiking map and
              // RudyMap's data carries no indoor levels.
              MapsforgeView(
                mapModel: model,
                children: [
                  DistanceOverlay(mapModel: model),
                  ZoomOverlay(mapModel: model),
                  RotationResetOverlay(mapModel: model),
                ],
              ),
              MarkerDatastoreOverlay(
                mapModel: model,
                datastore: _markers,
                zoomlevelRange: const ZoomlevelRange(0, 21),
              ),
            ],
          );
        },
      ),
    );
  }
}
