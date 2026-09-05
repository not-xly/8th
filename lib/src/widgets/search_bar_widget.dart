import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Barra de búsqueda. Escucha el TextEditingController directamente para
/// que el botón de limpiar aparezca/desaparezca AL INSTANTE con el texto
/// (antes dependía de la query confirmada por el debounce: 250ms tarde).
class SearchBarWidget extends StatefulWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final ValueChanged<String> onChanged;
  final VoidCallback onClear;
  final VoidCallback onClose;

  const SearchBarWidget({
    super.key,
    required this.controller,
    required this.focusNode,
    required this.onChanged,
    required this.onClear,
    required this.onClose,
  });

  @override
  State<SearchBarWidget> createState() => _SearchBarWidgetState();
}

class _SearchBarWidgetState extends State<SearchBarWidget> {
  void _onTextChanged() {
    if (mounted) setState(() {});
  }

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onTextChanged);
  }

  @override
  void didUpdateWidget(covariant SearchBarWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onTextChanged);
      widget.controller.addListener(_onTextChanged);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onTextChanged);
    super.dispose();
  }

  // Caja de icono 34x34 idéntica a la lupa (sin tap-target de IconButton)
  Widget _iconBox(BuildContext context, {required IconData icon, required VoidCallback onTap, String? tooltip}) {
    return Tooltip(
      message: tooltip ?? '',
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.06),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppTheme.glassBorder, width: 0.7),
          ),
          child: Icon(icon, size: 18, color: AppTheme.textSecondary),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final aura = Theme.of(context).colorScheme.primary;
    final query = widget.controller.text;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: AppTheme.blurSigmaSoft, sigmaY: AppTheme.blurSigmaSoft),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: AppTheme.glassSurface.withValues(alpha: 0.64),
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
              border: Border.all(color: aura.withValues(alpha: 0.18), width: 0.8),
              boxShadow: [
                BoxShadow(color: Colors.black.withValues(alpha: 0.18), blurRadius: 16, offset: const Offset(0, 6)),
              ],
            ),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: aura.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: aura.withValues(alpha: 0.16), width: 0.7),
                  ),
                  child: Icon(Icons.search_rounded, color: aura, size: 18),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: widget.controller,
                    focusNode: widget.focusNode,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w400, color: AppTheme.textPrimary),
                    decoration: const InputDecoration(
                      hintText: 'Título, artista o álbum...',
                      border: InputBorder.none,
                      hintStyle: TextStyle(color: AppTheme.textTertiary, fontSize: 14, fontWeight: FontWeight.w400),
                    ),
                    // La tecla "buscar" del teclado solo cierra el teclado:
                    // los resultados siguen visibles.
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => widget.focusNode.unfocus(),
                    onChanged: widget.onChanged,
                  ),
                ),
                if (query.isNotEmpty) ...[
                  _iconBox(context, icon: Icons.close_rounded, onTap: widget.onClear, tooltip: 'Limpiar'),
                  const SizedBox(width: 8),
                ],
                _iconBox(context, icon: Icons.arrow_back_rounded, onTap: widget.onClose, tooltip: 'Cerrar búsqueda'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
