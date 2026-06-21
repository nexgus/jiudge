import 'package:flutter/material.dart';

/// Route planning (Phase 2): tap start/end + waypoints, snap to OSM trails,
/// show length / ascent / elevation profile.
///
/// TODO(spec §1.1 F2/F3, Phase 2): implement planning UI + GraphHopper routing.
class PlanningScreen extends StatelessWidget {
  const PlanningScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: Text('Planning - Phase 2')));
  }
}
