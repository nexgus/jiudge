import 'package:flutter/material.dart';

/// App-shell theme (buttons, panels, chrome) only.
///
/// NOTE: this does NOT style the map canvas. Map cartography comes entirely
/// from RudyMap's bundled render theme and is never customized here.
/// See CLAUDE.md "Consume RudyMap data as-is".
class JiudgeTheme {
  const JiudgeTheme._();

  static ThemeData light() {
    return ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2E7D32)),
      useMaterial3: true,
    );
  }
}
