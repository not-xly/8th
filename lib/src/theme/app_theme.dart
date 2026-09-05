import 'package:flutter/material.dart';

/// iOS 26 Liquid Glass theme for 8th — optimized for OnePlus 15 / high-refresh
/// - Blur only on fixed chrome (header/mini/sheets) — cards use cheap translucent color
/// - Ultra-thin borders (white 6-7%), soft shadows, large radii
/// - Palette refinada estilo Apple: negro OLED + grises iOS + acento rojo desaturado
class AppTheme {
  // Palette — refinada, menos saturación, más Apple
  static const background = Color(0xFF09090B); // zinc-950, OLED black
  static const backgroundGradientTop = Color(0xFF0F0F12);
  static const backgroundGradientBottom = Color(0xFF09090B);

  static const glassSurface = Color(0xFF1C1C1E); // iOS dark surface
  static const glassSurfaceLight = Color(0xFF2C2C2E); // iOS secondary
  static const glassBorder = Color(0x0FFFFFFF); // white 6% — más sutil
  static const glassBorderStrong = Color(0x14FFFFFF); // white 8%

  static const primary = Color(0xFFFF3B30); // iOS system red, más suave que DC2626
  static const primarySoft = Color(0xFFFF6B6B);
  static const primaryMuted = Color(0x33FF3B30); // 20%
  static const textPrimary = Color(0xFFF5F5F7); // Apple off-white
  static const textSecondary = Color(0xFF8E8E93); // iOS secondary label
  static const textTertiary = Color(0xFF636366); // iOS tertiary

  static const radiusXL = 26.0;
  static const radiusL = 18.0;
  static const radiusM = 14.0;
  static const radiusS = 10.0;

  static const blurSigma = 20.0; // reducido de 24 para performance
  static const blurSigmaSoft = 14.0;
  // Solo header/mini/sheets usan blur — cards no
  static const bool useBlurForCards = false;

  static ThemeData get darkLiquidGlass => themeFor(primary);

  static ThemeData themeFor(Color primaryColor) {
    return ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: background,
      useMaterial3: true,
      // Sin fontFamily explícita: '.SF Pro Text' no existe en Android y
      // forzaba fallback a Roboto de todos modos. En iOS se usa San
      // Francisco por defecto y en Android Roboto, el comportamiento nativo.
      colorScheme: ColorScheme.dark(
        primary: primaryColor,
        secondary: primarySoft,
        surface: glassSurface,
      ),
      textTheme: const TextTheme(
        titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, letterSpacing: -0.5, color: textPrimary),
        titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: -0.3, color: textPrimary),
        bodyLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w400, color: textPrimary),
        bodyMedium: TextStyle(fontSize: 14, fontWeight: FontWeight.w400, color: textSecondary),
        labelLarge: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, letterSpacing: 0.2, color: textPrimary),
      ),
      iconTheme: const IconThemeData(color: textSecondary, size: 20),
    );
  }

  /// Reusable liquid glass decoration — cheap (no blur) para cards en lista/grid
  static BoxDecoration glassDecoration({
    double radius = radiusM,
    Color color = glassSurface,
    double opacity = 0.62,
    Border? border,
  }) {
    return BoxDecoration(
      color: color.withValues(alpha: opacity),
      borderRadius: BorderRadius.circular(radius),
      border: border ?? Border.all(color: glassBorder, width: 0.7),
      // Sombra sutil única — más barata que doble blur
      boxShadow: [
        BoxShadow(color: Colors.black.withValues(alpha: 0.18), blurRadius: 12, offset: const Offset(0, 4), spreadRadius: -2),
      ],
    );
  }

  /// Para chrome fijo (header/mini/sheet) — con blur, se usa con BackdropFilter aparte
  static BoxDecoration glassChromeDecoration({double radius = radiusM, double opacity = 0.58}) {
    return BoxDecoration(
      color: glassSurface.withValues(alpha: opacity),
      borderRadius: BorderRadius.circular(radius),
      border: Border.all(color: glassBorderStrong, width: 0.7),
      boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.24), blurRadius: 16, offset: const Offset(0, 6), spreadRadius: -2)],
    );
  }

  static BoxDecoration glassCardDecoration({double radius = radiusM, bool isSelected = false, bool isPlaying = false}) {
    if (isSelected || isPlaying) {
      return BoxDecoration(
        color: const Color(0xFF2A1D1E).withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: primary.withValues(alpha: 0.85), width: 1.0),
        boxShadow: [BoxShadow(color: primary.withValues(alpha: 0.14), blurRadius: 10, offset: const Offset(0, 3))],
      );
    }
    return glassDecoration(radius: radius);
  }

  /// Versión del color dinámico (aura) con menos saturación, para el
  /// texto/icono de la canción seleccionada. El borde/brillo mantiene
  /// el aura viva; solo el contenido se suaviza.
  static Color desaturated(Color color, [double amount = 0.45]) {
    final hsl = HSLColor.fromColor(color);
    return hsl
        .withSaturation((hsl.saturation * (1 - amount)).clamp(0.0, 1.0))
        .toColor();
  }

  static LinearGradient get backgroundGradient => const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [backgroundGradientTop, backgroundGradientBottom],
      );

  static LinearGradient get subtleHighlight => LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Colors.white.withValues(alpha: 0.06), Colors.transparent],
      );
}
