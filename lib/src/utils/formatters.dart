String fmtMs(int ms) {
  final d = Duration(milliseconds: ms);
  final m = d.inMinutes.remainder(60);
  final s = d.inSeconds.remainder(60);
  final h = d.inHours;
  if (h > 0) {
    return '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }
  return '$m:${s.toString().padLeft(2, '0')}';
}
