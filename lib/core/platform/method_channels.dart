/// Platform-channel names shared between Dart and native (Kotlin) code.
///
/// Native logic sits behind these channels for clean separation (Android-only;
/// not a cross-platform abstraction). See CLAUDE.md "Android only".
class MethodChannels {
  const MethodChannels._();

  static const String location = 'io.github.nexgus.jiudge/location';
  static const String routing = 'io.github.nexgus.jiudge/routing';
}
