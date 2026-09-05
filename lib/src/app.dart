import 'package:flutter/material.dart';

import 'screens/music_player_screen.dart';
import 'theme/app_theme.dart';
import 'theme/palette_service.dart';

class EighthApp extends StatelessWidget {
  const EighthApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Color>(
      valueListenable: PaletteService.auraColor,
      builder: (context, aura, _) {
        return MaterialApp(
          title: '8th',
          debugShowCheckedModeBanner: false,
          theme: AppTheme.themeFor(aura),
          themeAnimationDuration: const Duration(milliseconds: 440),
          themeAnimationCurve: Curves.easeInOutCubic,
          home: const MusicPlayerScreen(),
        );
      },
    );
  }
}
