import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'glass_notification.dart';

class InfoDialog extends StatelessWidget {
  final int songCount;
  final int albumCount;
  final int artistCount;
  final Future<int> Function()? onClearCache;

  const InfoDialog({
    super.key,
    required this.songCount,
    required this.albumCount,
    required this.artistCount,
    this.onClearCache,
  });

  static Future<void> show(BuildContext context,
      {required int songCount,
      required int albumCount,
      required int artistCount,
      Future<int> Function()? onClearCache}) {
    return showDialog(
      context: context,
      builder: (ctx) => InfoDialog(
          songCount: songCount,
          albumCount: albumCount,
          artistCount: artistCount,
          onClearCache: onClearCache),
    );
  }

  Widget _statLine(BuildContext context, String label, int value) {
    final aura = Theme.of(context).colorScheme.primary;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: aura.withValues(alpha: 0.85), shape: BoxShape.circle),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              label,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppTheme.textSecondary),
            ),
          ),
          Text(
            '$value',
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, letterSpacing: -0.3, color: AppTheme.textPrimary),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final aura = Theme.of(context).colorScheme.primary;
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: AppTheme.blurSigma, sigmaY: AppTheme.blurSigma),
          child: Container(
            padding: const EdgeInsets.fromLTRB(20, 22, 20, 14),
            decoration: AppTheme.glassDecoration(radius: AppTheme.radiusL, opacity: 0.78),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: aura.withValues(alpha: 0.14),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: aura.withValues(alpha: 0.16), width: 0.8),
                      ),
                      child: Icon(Icons.music_note_rounded, color: aura, size: 18),
                    ),
                    const SizedBox(width: 10),
                    const Text('8th', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: -0.3, color: AppTheme.textPrimary)),
                  ],
                ),
                const SizedBox(height: 20),
                _statLine(context, 'Artistas', artistCount),
                _statLine(context, 'Álbumes', albumCount),
                _statLine(context, 'Canciones', songCount),
                if (onClearCache != null) ...[
                  const SizedBox(height: 14),
                  // Explica QUÉ borra exactamente (antes era un botón
                  // misterioso: no está claro si toca tus archivos).
                  const Text(
                    '«Limpiar caché» borra las portadas guardadas en disco; se vuelven a generar solas al navegar. No borra canciones ni letras.',
                    style: TextStyle(
                      fontSize: 11.5,
                      color: AppTheme.textTertiary,
                      height: 1.35,
                    ),
                  ),
                  const SizedBox(height: 10),
                ],
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    if (onClearCache != null)
                      TextButton(
                        onPressed: () async {
                          final n = await onClearCache!();
                          if (context.mounted) {
                            Navigator.pop(context);
                            GlassNotification.show(
                              context,
                              n == 0
                                  ? 'Caché ya estaba limpia'
                                  : 'Caché limpiada ($n portadas)',
                              icon: Icons.cleaning_services_rounded,
                            );
                          }
                        },
                        style: TextButton.styleFrom(
                            foregroundColor: AppTheme.textSecondary,
                            padding: const EdgeInsets.symmetric(
                                horizontal: 14, vertical: 8)),
                        child: const Text('Limpiar caché',
                            style: TextStyle(
                                fontSize: 13, fontWeight: FontWeight.w500)),
                      ),
                    // Sin blur en el botón (el dialog entero ya difumina
                    // detrás): un render pass offscreen menos por dialog.
                    Container(
                      decoration: BoxDecoration(
                        color: aura.withValues(alpha: 0.94),
                        borderRadius: BorderRadius.circular(AppTheme.radiusM),
                        border: Border.all(color: aura.withValues(alpha: 0.9), width: 0.8),
                      ),
                      child: TextButton(
                        onPressed: () => Navigator.pop(context),
                        style: TextButton.styleFrom(foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8)),
                        child: const Text('Entendido', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
