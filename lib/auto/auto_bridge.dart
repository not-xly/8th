import 'dart:async';

import 'package:flutter/services.dart';

class AutoBridge {
  static const MethodChannel _method =
      MethodChannel('com.xly.u_8th/files');
  static const EventChannel _events =
      EventChannel('com.xly.u_8th/auto_events');

  // Stream único cacheado: receiveBroadcastStream() registra un receptor
  // nativo NUEVO en cada llamada, así que exponerlo como getter creaba un
  // receptor por suscripción.
  static final Stream<Map<String, dynamic>> events =
      _events.receiveBroadcastStream().map((e) => Map<String, dynamic>.from(e));

  static void pushPlaybackSnapshot(Map<String, dynamic> snapshot) {
    // Fire-and-forget sin warnings de unhandled: si el engine está
    // detached (fondos/tests), se ignora.
    try {
      _method.invokeMethod('pushPlaybackSnapshot', snapshot).catchError((_) {});
    } catch (_) {}
  }
}
