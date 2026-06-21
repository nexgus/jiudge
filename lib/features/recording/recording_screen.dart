import 'package:flutter/material.dart';

/// Track recording: always-on record button, live stats overlay, background
/// recording via a foreground service.
///
/// TODO(spec §1.1 F4/F5/F6, Phase 1): implement recording UI + live stats.
class RecordingScreen extends StatelessWidget {
  const RecordingScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(child: Text('Recording - not implemented yet')),
    );
  }
}
