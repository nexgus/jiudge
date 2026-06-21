import 'package:flutter/material.dart';

import 'features/map/map_screen.dart';
import 'ui/theme/app_theme.dart';

/// Root application widget.
///
/// Phase 0/1 opens directly on the map. Navigation to the other feature
/// screens (planning, recording, settings) is wired up in later phases.
class JiudgeApp extends StatelessWidget {
  const JiudgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Jiudge',
      theme: JiudgeTheme.light(),
      home: const MapScreen(),
    );
  }
}
