import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:jiudge/app.dart';

void main() {
  testWidgets('App boots to the map screen', (WidgetTester tester) async {
    await tester.pumpWidget(const JiudgeApp());

    // Placeholder until mapsforge_flutter is integrated.
    expect(find.byType(MaterialApp), findsOneWidget);
    expect(find.textContaining('Map'), findsOneWidget);
  });
}
