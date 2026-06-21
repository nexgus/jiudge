import 'package:flutter/material.dart';

/// Settings: map data management, GPS sampling rate, language toggle, etc.
///
/// TODO(spec §5): implement settings UI; map data lives in [MapDataScreen].
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(child: Text('Settings - not implemented yet')),
    );
  }
}
