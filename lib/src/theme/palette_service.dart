import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:palette_generator/palette_generator.dart';

import 'app_theme.dart';
import '../utils/hashing.dart';

/// Servicio global de paleta — pre-calcula el color de la portada en background
/// para que al abrir FullPlayer ya esté listo y el cambio sea progresivo.
class PaletteService {
  static final ValueNotifier<Color> auraColor = ValueNotifier<Color>(AppTheme.primary);
  // LRU acotada (antes crecía sin límite, una entrada por portada vista).
  static final Map<int, Color> _cache = <int, Color>{};
  static const int _maxCache = 60;
  static int _lastHash = 0;
  static bool _hasLast = false;
  static bool _scheduled = false;
  static Color? _pendingTarget;

  static const _fallback = AppTheme.primary;

  static void _putCache(int hash, Color c) {
    // Re-inserta para marcar como reciente (LinkedHashMap).
    _cache.remove(hash);
    _cache[hash] = c;
    while (_cache.length > _maxCache) {
      _cache.remove(_cache.keys.first);
    }
  }

  /// Llamado por MusicPlayerController cuando cambia currentSong
  static Future<void> updateForArt(Uint8List? art) async {
    if (art == null || art.isEmpty) {
      _hasLast = false;
      _lastHash = 0;
      _animateTo(_fallback);
      return;
    }
    final hash = _artHash(art);
    // Dedup por contenido (antes `identical` fallaba cuando los mismos bytes
    // se recargaban como nuevo Uint8List y repetía el decode).
    if (_hasLast && hash == _lastHash) return;
    _hasLast = true;
    _lastHash = hash;
    if (_cache.containsKey(hash)) {
      _animateTo(_cache[hash]!);
      return;
    }
    // No bloquear navegación: palette mini 40x40, 8 colores, con pequeño delay para no competir con transición
    try {
      // Delay 80ms para que la transición de apertura no compita con el decode
      await Future.delayed(const Duration(milliseconds: 80));
      if (!_hasLast || hash != _lastHash) return;
      final palette = await PaletteGenerator.fromImageProvider(
        MemoryImage(art),
        size: const Size(40, 40),
        maximumColorCount: 8,
      );
      final color = palette.vibrantColor?.color ?? palette.dominantColor?.color ?? _fallback;
      _putCache(hash, color);
      if (_hasLast && hash == _lastHash) {
        _animateTo(color);
      }
    } catch (_) {
      _animateTo(_fallback);
    }
  }

  /// FNV-1a sobre muestreo (512 muestras + longitud): el hash anterior
  /// (length ^ art[0] ^ art[100] ^ art[500]) colisionaba entre portadas
  /// del mismo tamaño. Muestrear evita hashear MBs en el hilo UI.
  static int _artHash(Uint8List art) => fnv1aBytesSampled(art);

  static void _animateTo(Color target) {
    // El ValueNotifier dispara AnimatedTheme / AnimatedContainer en UI (400ms easeInOutCubic)
    // Coalesca llamadas rápidas (skips): antes cada tema encolaba un
    // postFrameCallback y parpadeaba por colores intermedios.
    if (auraColor.value == target && !_scheduled) return;
    _pendingTarget = target;
    if (_scheduled) return;
    _scheduled = true;
    // Usamos postFrame para no notificar durante build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      final t = _pendingTarget;
      _pendingTarget = null;
      if (t != null && auraColor.value != t) auraColor.value = t;
    });
  }

  static void reset() {
    _hasLast = false;
    _lastHash = 0;
    _pendingTarget = null;
    auraColor.value = _fallback;
  }

  @visibleForTesting
  static int cacheSize() => _cache.length;
}
