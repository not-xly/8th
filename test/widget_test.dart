import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eighth/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('App loads successfully', (WidgetTester tester) async {
    await tester.pumpWidget(const EighthApp());
    await tester.pump(const Duration(milliseconds: 100));
    // Header de la app (no depende de diálogos que también usan '8th').
    expect(find.byType(MaterialApp), findsOneWidget);
    expect(find.text('8th'), findsWidgets);
  });
}
