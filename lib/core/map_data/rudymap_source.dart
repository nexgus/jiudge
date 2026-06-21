/// RudyMap (MOI.OSM Taiwan TOPO) download endpoints.
///
/// Data is fetched directly from RudyMap's public mirrors for personal use
/// only; nothing is re-hosted or redistributed. Filenames are constant; the
/// content behind them changes weekly (Thursdays). There is no manifest and no
/// published checksum. See spec §2.3.
class RudymapSource {
  const RudymapSource._();

  /// Mirror base URLs, tried in order with failover.
  static const List<String> mirrors = <String>[
    'https://rudymap.tw/v1/',
    'https://moi.kcwu.csie.org/',
    'https://map.happyman.idv.tw/rudy/',
  ];

  /// Basemap (mapsforge `.map`, zipped). Full ~298 MB, Lite ~168 MB.
  static const String basemapFile = 'MOI_OSM_Taiwan_TOPO_Rudy.map.zip';
  static const String basemapLiteFile = 'MOI_OSM_Taiwan_TOPO_Lite.map.zip';

  /// DEM (`.hgt`, zipped). Mix = Taiwan 30 m + islands 90 m (~46 MB);
  /// the 90 m uniform set is ~8 MB.
  static const String demMixFile = 'hgtmix.zip';
  static const String dem90File = 'hgt90.zip';

  /// RudyMap's bundled render theme (light + dark variants).
  static const String styleFile = 'MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip';
}
