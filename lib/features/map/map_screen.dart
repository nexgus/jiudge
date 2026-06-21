import 'package:flutter/material.dart';

/// Map view: renders a RudyMap `.map` via `mapsforge_flutter`.
///
/// TODO(spec §2.1): integrate mapsforge_flutter and load the RudyMap basemap.
/// TODO(spec §1.1 F1): continuous pinch-zoom + rotation (no 3D tilt).
class MapScreen extends StatelessWidget {
  const MapScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Text('Map (mapsforge_flutter) - not implemented yet'),
      ),
    );
  }
}
