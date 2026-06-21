import 'package:flutter/foundation.dart';
import 'package:mapsforge_flutter_rendertheme/rendertheme.dart';
import 'package:xml/xml.dart';

/// Debug-only workaround for an over-strict assertion in `mapsforge_flutter`'s
/// rendertheme parser (`rule.dart`): a `<rule>` whose sub-rules all build empty
/// (every nested instruction gets gated out by element/closed/visibility rules)
/// trips `assert(... has instructions ...)`.
///
/// The assertion is a Dart `assert`, compiled out in release/profile (AOT), so
/// shipped apps are unaffected and consume RudyMap's theme verbatim. This only
/// keeps `flutter run` (debug/JIT, asserts on) from crashing on the map screen.
///
/// Strategy: build each top-level `<rule>` in isolation and drop the ones that
/// throw an `AssertionError`. This is self-adapting to RudyMap's weekly theme
/// updates - no hard-coded rule ids. As of the 2026.06.18 theme it drops a
/// single rule (the `landscapename` area-label group), so debug builds lose
/// only those italic area-name captions; release keeps everything.
class ThemeSanitizer {
  const ThemeSanitizer._();

  /// Returns [themeXml] with build-failing top-level rules removed. Intended to
  /// be called only when [kDebugMode] is true; in release pass the theme
  /// through untouched.
  static String stripBuildFailingTopRules(String themeXml) {
    final XmlDocument doc = XmlDocument.parse(themeXml);
    final XmlElement root = doc.rootElement;
    final List<String> dropped = <String>[];

    for (final XmlElement rule
        in root.childElements.where((e) => e.name.local == 'rule').toList()) {
      if (_buildFailsInIsolation(root, rule)) {
        dropped.add(
          rule.getAttribute('cat') ?? rule.getAttribute('k') ?? 'rule',
        );
        rule.parent?.children.remove(rule);
      }
    }

    if (dropped.isNotEmpty) {
      debugPrint('ThemeSanitizer: dropped debug-incompatible rules: $dropped');
    }
    return doc.toXmlString();
  }

  /// Wraps [rule] alone under a fresh `<rendertheme>` carrying [root]'s
  /// attributes and tries to parse it; true if the parser asserts on it.
  static bool _buildFailsInIsolation(XmlElement root, XmlElement rule) {
    final XmlElement probe = XmlElement(
      XmlName.fromString(root.name.qualified),
      root.attributes.map((a) => a.copy()),
      <XmlNode>[rule.copy()],
    );
    try {
      RenderThemeBuilder.createFromString(
        XmlDocument(<XmlNode>[probe]).toXmlString(),
      );
      return false;
    } on AssertionError {
      return true;
    } catch (_) {
      // Any other failure isn't the empty-rule assertion we target; leave the
      // rule in place so behaviour matches the unsanitised parse.
      return false;
    }
  }
}
