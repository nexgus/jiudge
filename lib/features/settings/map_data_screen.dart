import 'package:flutter/material.dart';

/// Map data management: check for updates, download from RudyMap mirrors,
/// manual local import (offline fallback).
///
/// TODO(spec §2.3, §4.3, F8): implement update check + download + local import.
class MapDataScreen extends StatelessWidget {
  const MapDataScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(child: Text('Map data - not implemented yet')),
    );
  }
}
