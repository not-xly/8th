import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class EmptyState extends StatelessWidget {
  final VoidCallback onPickFolder;

  const EmptyState({super.key, required this.onPickFolder});

  @override
  Widget build(BuildContext context) {
    // Sin BackdropFilter: esta pantalla solo aparece sin biblioteca, sobre
    // un fondo degradado estático que el blur no cambia; dos render passes
    // offscreen para nada.
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(28),
              decoration: BoxDecoration(
                color: AppTheme.glassSurface.withValues(alpha: 0.64),
                shape: BoxShape.circle,
                border: Border.all(color: AppTheme.glassBorder, width: 0.9),
                boxShadow: [
                  BoxShadow(color: Colors.black.withValues(alpha: 0.28), blurRadius: 24, offset: const Offset(0, 8)),
                ],
              ),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.12),
                  shape: BoxShape.circle,
                  border: Border.all(color: AppTheme.primary.withValues(alpha: 0.16), width: 0.8),
                ),
                child: const Icon(Icons.album_rounded, size: 48, color: AppTheme.primary),
              ),
            ),
            const SizedBox(height: 20),
            const Text('No hay álbumes cargados', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, letterSpacing: -0.4, color: AppTheme.textPrimary)),
            const SizedBox(height: 8),
            const Text(
              'Selecciona una carpeta o archivos de audio para cargar tu música.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 14, fontWeight: FontWeight.w400, height: 1.4),
            ),
            const SizedBox(height: 24),
            Container(
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.94),
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
                border: Border.all(color: AppTheme.primary.withValues(alpha: 0.9), width: 0.8),
                boxShadow: [BoxShadow(color: AppTheme.primary.withValues(alpha: 0.24), blurRadius: 12, offset: const Offset(0, 4))],
              ),
              child: TextButton.icon(
                style: TextButton.styleFrom(foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12)),
                icon: const Icon(Icons.folder_open_rounded, size: 18),
                label: const Text('Seleccionar carpeta', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, letterSpacing: 0.2)),
                onPressed: onPickFolder,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
