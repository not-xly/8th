import 'dart:ui';
import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class GlassNotification {
  static OverlayEntry? _current;
  static void hide() {
    final entry = _current;
    _current = null;
    if (entry == null) return;
    try {
      entry.remove();
    } catch (_) {
      // El overlay ya se eliminó (pop de pantalla): ignorar.
    }
  }

  static void show(
    BuildContext context,
    String message, {
    IconData icon = Icons.lyrics_rounded,
    Duration duration = const Duration(seconds: 2),
    Color? iconColor,
  }) {
    hide();
    OverlayState? overlay;
    try {
      overlay = Overlay.of(context);
    } catch (_) {
      return;
    }
    final entry = OverlayEntry(
      builder: (ctx) => _GlassNotificationWidget(
        message: message,
        icon: icon,
        iconColor: iconColor,
        onDismiss: hide,
      ),
    );
    _current = entry;
    try {
      overlay.insert(entry);
    } catch (_) {
      _current = null;
      return;
    }
    Future.delayed(duration, () {
      if (_current == entry) hide();
    });
  }
}

class _GlassNotificationWidget extends StatefulWidget {
  final String message;
  final IconData icon;
  final Color? iconColor;
  final VoidCallback onDismiss;
  const _GlassNotificationWidget({required this.message, required this.icon, this.iconColor, required this.onDismiss});
  @override
  State<_GlassNotificationWidget> createState() => _GlassNotificationWidgetState();
}

class _GlassNotificationWidgetState extends State<_GlassNotificationWidget> with SingleTickerProviderStateMixin {
  late AnimationController _c;
  late Animation<double> _fade;
  late Animation<Offset> _slide;
  @override
  void initState() {
    super.initState();
    _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 320));
    _fade = CurvedAnimation(parent: _c, curve: Curves.easeOutCubic);
    _slide = Tween<Offset>(begin: const Offset(0, 0.12), end: Offset.zero).animate(CurvedAnimation(parent: _c, curve: Curves.easeOutCubic));
    _c.forward();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  void _dismiss() async {
    await _c.reverse();
    widget.onDismiss();
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Positioned(
      left: 12,
      right: 12,
      bottom: 88 + bottomInset * 0.2, // por encima del mini player
      child: SafeArea(
        child: FadeTransition(
          opacity: _fade,
          child: SlideTransition(
            position: _slide,
            child: Material(
              color: Colors.transparent,
              child: GestureDetector(
                onTap: _dismiss,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: AppTheme.blurSigmaSoft, sigmaY: AppTheme.blurSigmaSoft),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                      decoration: BoxDecoration(
                        color: AppTheme.glassSurface.withValues(alpha: 0.78),
                        borderRadius: BorderRadius.circular(AppTheme.radiusL),
                        border: Border.all(color: AppTheme.glassBorderStrong, width: 0.8),
                        boxShadow: [
                          BoxShadow(color: Colors.black.withValues(alpha: 0.28), blurRadius: 20, offset: const Offset(0, 8)),
                          BoxShadow(color: Colors.black.withValues(alpha: 0.12), blurRadius: 6, offset: const Offset(0, 2)),
                        ],
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            padding: const EdgeInsets.all(7),
                            decoration: BoxDecoration(
                              color: (widget.iconColor ?? Theme.of(context).colorScheme.primary).withValues(alpha: 0.14),
                              shape: BoxShape.circle,
                              border: Border.all(color: (widget.iconColor ?? Theme.of(context).colorScheme.primary).withValues(alpha: 0.22), width: 0.7),
                            ),
                            child: Icon(widget.icon, size: 16, color: widget.iconColor ?? Theme.of(context).colorScheme.primary),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              widget.message,
                              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500, letterSpacing: -0.1, color: AppTheme.textPrimary, height: 1.25),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 8),
                          const Icon(Icons.close_rounded, size: 14, color: AppTheme.textTertiary),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
