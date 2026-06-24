#!/usr/bin/env python3
"""Single source of truth for the map-symbol reference and the in-app identify table.

Reads RudyMap's render theme (MOI_OSM.xml) and emits:
  - docs/map-symbols.md          human-readable reference (value -> key -> 中文), with version
  - app/src/main/assets/symbols.json   data the "?" identify feature loads at runtime

The theme lives under mapdata/ (git-ignored). Use --download to fetch the latest theme bundle from
RudyMap's mirrors; if its version matches what we already generated, generation is skipped (use
--force to regenerate anyway, e.g. after editing the translations below).

Usage:
  python scripts/gen_symbols.py            # generate from mapdata/MOI_OSM.xml
  python scripts/gen_symbols.py --download # fetch latest theme first (skips if version unchanged)
  python scripts/gen_symbols.py --force    # regenerate even if the version is unchanged
"""
import argparse
import json
import os
import re
import sys
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
import zipfile
import io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPDATA = os.path.join(ROOT, "mapdata")
THEME = os.path.join(MAPDATA, "MOI_OSM.xml")
META = os.path.join(MAPDATA, ".theme_meta.json")
DOC = os.path.join(ROOT, "docs", "map-symbols.md")
JSON_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "symbols.json")

THEME_ZIP = "MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip"
MIRRORS = [
    "https://moi.kcwu.csie.org/",
    "https://map.happyman.idv.tw/rudy/",
    "https://rudymap.tw/v1/",
]

# --- feature keys (object/symbol-defining); pure modifiers (surface, ele, zl, color...) excluded ---
FEATURE = {'natural', 'tourism', 'amenity', 'highway', 'waterway', 'man_made', 'historic',
 'boundary', 'leisure', 'landuse', 'railway', 'aerialway', 'aeroway', 'power', 'barrier',
 'shop', 'religion', 'information', 'place', 'sport', 'memorial', 'emergency', 'mountain_pass',
 'survey_point', 'building', 'military', 'route', 'ford', 'ladder', 'rungs', 'safety_rope',
 'via_ferrata_flag', 'shelter', 'shelter_type', 'generator:source', 'piste:type', 'tower',
 'station', 'climbing', 'vending', 'cycle_node', 'checkpoint:type'}
RENDER = {'symbol', 'line', 'area', 'circle', 'pathText', 'lineSymbol'}
SKIP_VAL = {'~', '*'}

# Order to resolve a feature's tags: most specific point feature first, broad area/admin last.
KEY_PRIORITY = ['mountain_pass', 'emergency', 'survey_point', 'man_made', 'tower', 'historic',
 'memorial', 'tourism', 'information', 'amenity', 'natural', 'shelter_type', 'shelter',
 'climbing', 'ladder', 'rungs', 'safety_rope', 'via_ferrata_flag', 'aerialway', 'aeroway',
 'station', 'railway', 'power', 'generator:source', 'barrier', 'sport', 'leisure', 'ford',
 'waterway', 'shop', 'vending', 'highway', 'route', 'place', 'piste:type', 'boundary',
 'landuse', 'building', 'military', 'cycle_node', 'checkpoint:type']

SURFACE = {
 'asphalt': '柏油', 'smooth_paved': '平整鋪面', 'rough_paved': '粗糙鋪面', 'compacted': '夯實碎石',
 'gravel': '碎石', 'unpaved': '未鋪面', 'raw': '原始地面', 'winter': '雪季路況',
}
ADMIN_LEVEL = {'2': '國界', '4': '縣市界線', '6': '縣市界線', '7': '鄉鎮市區界線',
 '8': '鄉鎮市區界線', '9': '村里界線'}

# value -> 中文 (default). Context-specific meanings handled in CTX[(value, key)].
TR = {
 'motorway': '高速公路', 'motorway_link': '高速公路匝道', 'motorway_junction': '交流道',
 'trunk': '快速道路', 'trunk_link': '快速道路匝道', 'primary': '主要道路',
 'primary_link': '主要道路匝道', 'secondary': '次要道路', 'secondary_link': '次要道路匝道',
 'tertiary': '三級道路', 'tertiary_link': '三級道路匝道', 'unclassified': '未分級道路',
 'residential': '住宅區道路', 'living_street': '生活化道路', 'service': '服務道路',
 'pedestrian': '行人徒步區', 'track': '林道', 'path': '登山步道', 'footway': '人行步道',
 'bridleway': '馬道', 'cycleway': '自行車道', 'steps': '階梯', 'road': '道路', 'byway': '鄉間小道',
 'raceway': '賽道', 'bus_guideway': '公車專用道', 'construction': '施工中',
 'mini_roundabout': '小型圓環', 'turning_circle': '迴車道', 'traffic_signals': '紅綠燈',
 'trailhead': '登山口', 'emergency_access_point': '緊急救護點', 'platform': '月台',
 'bus_stop': '公車站', 'crossing': '平交道',
 'rail': '鐵路', 'light_rail': '輕軌', 'narrow_gauge': '窄軌鐵路', 'subway': '地鐵',
 'tram': '路面電車', 'tram_stop': '電車站', 'funicular': '纜索鐵道', 'preserved': '保存鐵道',
 'miniature': '迷你鐵道', 'halt': '招呼站', 'level_crossing': '平交道', 'monorail': '單軌鐵路',
 'cable_car': '纜車', 'gondola': '吊廂纜車', 'chair_lift': '吊椅纜車', 'drag_lift': '拖曳索道',
 'magic_carpet': '輸送帶纜道', 'rope_tow': '繩索拖曳', 'mixed_lift': '混合纜車', 'goods': '貨運纜車',
 'aerodrome': '機場', 'airport': '機場', 'apron': '停機坪', 'runway': '跑道', 'taxiway': '滑行道',
 'helipad': '直升機坪', 'terminal': '航廈', 'ferry': '渡輪', 'ferry_terminal': '渡輪碼頭',
 'stream': '溪流', 'river': '河流', 'canal': '運河', 'ditch': '溝渠', 'drain': '排水溝',
 'dam': '水壩', 'weir': '攔河堰', 'waterfall': '瀑布', 'rapids': '急流', 'dock': '船塢',
 'water_point': '取水點',
 'peak': '山峰', 'volcano': '火山', 'tree': '樹木', 'giant_tree': '老樹', 'cliff': '懸崖',
 'cave_entrance': '洞穴口', 'spring': '湧泉', 'hot_spring': '溫泉', 'geyser': '間歇泉',
 'rock': '岩石', 'scree': '碎石坡', 'scrub': '灌叢', 'heath': '荒原', 'fell': '高地草坡',
 'grassland': '草原', 'grass': '草地', 'wood': '樹林', 'forest': '森林', 'beach': '海灘',
 'sand': '沙地', 'shingle': '礫灘', 'marsh': '沼澤', 'wetland': '濕地', 'glacier': '冰川',
 'water': '水域', 'sea': '海域', 'nosea': '非海域遮罩', 'canyon': '峽谷', 'gorge': '峽谷',
 'valley': '山谷', 'crater': '火山口', 'crevasse': '冰隙', 'fjord': '峽灣', 'desert': '沙漠',
 'peninsula': '半島',
 'farmland': '農田', 'farm': '農場', 'farmyard': '農莊', 'meadow': '牧草地', 'orchard': '果園',
 'vineyard': '葡萄園', 'allotments': '市民農園', 'commercial': '商業區', 'industrial': '工業區',
 'retail': '零售區', 'brownfield': '待開發地', 'greenfield': '未開發地', 'garages': '車庫區',
 'garden': '庭園', 'cemetery': '墓地', 'quarry': '採石場', 'landfill': '掩埋場',
 'reservoir': '水庫', 'basin': '蓄水池', 'recreation_ground': '遊憩地', 'village_green': '村里綠地',
 'field': '田地', 'grave_yard': '墓園',
 'park': '公園', 'playground': '遊樂場', 'pitch': '球場', 'stadium': '體育場',
 'sports_centre': '運動中心', 'golf_course': '高爾夫球場', 'golf': '高爾夫', 'swimming_pool': '游泳池',
 'swimming': '游泳', 'water_park': '水上樂園', 'dog_park': '寵物公園', 'common': '公共綠地',
 'slipway': '下水滑道', 'ls_track': '休閒步道', 'nature_reserve': '自然保護區',
 'atm': '自動提款機', 'bank': '銀行', 'bar': '酒吧', 'biergarten': '啤酒花園', 'cafe': '咖啡館',
 'pub': '酒館', 'restaurant': '餐廳', 'fast_food': '速食店', 'bench': '長椅', 'shelter': '遮蔽所',
 'drinking_water': '飲用水', 'toilets': '廁所', 'shower': '淋浴間', 'fountain': '噴泉',
 'hospital': '醫院', 'pharmacy': '藥局', 'doctors': '診所', 'fire_station': '消防隊',
 'police': '警察局 / 派出所', 'telephone': '公共電話', 'post_box': '郵筒', 'post_office': '郵局',
 'recycling': '回收站', 'library': '圖書館', 'school': '學校', 'college': '專科學校',
 'university': '大學', 'kindergarten': '幼兒園', 'cinema': '電影院', 'theatre': '劇院',
 'marketplace': '市集', 'parking': '停車場', 'fuel': '加油站', 'bus_station': '公車總站',
 'car': '汽車服務', 'bicycle': '自行車', 'bicycle_rental': '自行車租賃',
 'bicycle_rental_YouBike': 'YouBike 站', 'bicycle_rental_Moovo': 'Moovo 站', 'bicycle_tube': '自行車補給',
 'embassy': '大使館', 'place_of_worship': '宗教場所', 'mall': '購物中心', 'supermarket': '超市',
 'convenience': '便利商店', 'beverages': '飲料店', 'doityourself': '居家修繕店', 'butcher': '肉舖',
 'sports': '運動用品店', 'hostel': '青年旅館', 'hotel': '旅館', 'chalet': '度假小屋',
 'apartment': '公寓', 'air_defense_shelter': '防空避難所', 'office': '辦公處所',
 'books': '書店', 'chemist': '藥妝店', 'laundry': '洗衣店', 'organic': '有機商店',
 'travel_agency': '旅行社', 'bakery': '麵包店',
 'alpine_hut': '山屋', 'wilderness_hut': '荒野小屋', 'viewpoint': '觀景點', 'attraction': '景點',
 'museum': '博物館', 'information': '旅遊資訊', 'picnic_table': '野餐桌', 'guidepost': '指示牌',
 'board': '資訊看板', 'mobile': '行動資訊', 'trail_milestone': '里程碑', 'caravan_site': '露營車營地',
 'picnic_site': '野餐區', 'camp_site': '營地', 'zoo': '動物園',
 'archaeological_site': '考古遺址', 'castle': '城堡', 'memorial': '紀念物', 'monument': '紀念碑',
 'ruins': '廢墟', 'wayside_cross': '路旁十字架', 'wayside_shrine': '路旁神龕',
 'plaque': '紀念牌', 'statue': '紀念雕像', 'stone': '紀念石', 'stolperstein': '絆腳石紀念磚',
 'war_memorial': '戰爭紀念碑',
 'communications_tower': '通訊塔', 'mast': '桅桿', 'lighthouse': '燈塔', 'water_well': '水井',
 'windmill': '風車', 'pier': '棧橋', 'pipeline': '管線', 'groyne': '丁壩', 'dyke': '堤防',
 'embankment': '堤岸', 'emb_yes': '堤岸', 'summit_board': '山峰', 'survey_point': '測量基準點',
 'line': '高壓電線', 'minor_line': '配電線', 'plant': '發電廠', 'generator': '發電機組',
 'coal': '燃煤發電', 'gas': '燃氣發電', 'nuclear': '核能發電', 'hydro': '水力發電',
 'wind': '風力發電', 'tidal': '潮汐發電',
 'gate': '柵門', 'lift_gate': '升降桿', 'kissing_gate': '轉門', 'turnstile': '旋轉柵門',
 'cycle_barrier': '自行車檔', 'bollard': '車擋柱', 'block': '石墩', 'chain': '鐵鍊',
 'fence': '圍籬', 'wall': '牆', 'city_wall': '城牆', 'retaining_wall': '擋土牆',
 'border_control': '邊境管制', 'stile': '越牆梯磴',
 'buddhist': '佛教', 'christian': '基督教', 'muslim': '伊斯蘭教', 'hindu': '印度教',
 'jewish': '猶太教', 'shinto': '神道教',
 'city': '城市', 'town': '鄉鎮', 'village': '村莊', 'hamlet': '小村', 'county': '縣市',
 'climbing': '攀岩', 'soccer': '足球', 'tennis': '網球', 'shooting': '射擊',
 'cl_yes': '可攀岩', 'set': '攀岩鐵道', 'ld_yes': '梯子', 'rn_yes': '踏階', 'sr_yes': '安全繩',
 'fd_yes': '渡河處', 'ford': '渡河處',
 'phone': '緊急電話', 'defibrillator': 'AED 自動電擊器', 'landing_site': '直升機降落點',
 'portable_altitude_chamber': '攜帶式高壓氧艙',
 'basic_hut': '簡易山屋', 'lean_to': '遮雨棚', 'picnic_shelter': '野餐亭',
 'rock_shelter': '岩窟遮蔽', 'weather_shelter': '避雨亭',
 'communication': '通訊塔', 'observation': '瞭望塔',
 'downhill': '滑雪道', 'nordic': '越野滑雪道', 'sled': '雪橇道',
 'national_park': '國家公園界線', 'strict_protected': '嚴格保護區界線',
 'forest_compartment': '林班界', 'map_inner': '圖框內界',
 'yes': '是',
 'stamp': '紀念章打卡點',
 'military': '軍事用地', 'station': '車站', 'tower': '塔', 'disused': '停用', 'abandoned': '廢棄',
 'trig_1st': '一等三角點', 'trig_2nd': '二等三角點', 'trig_3rd': '三等三角點',
 'trig_1st_IC': '一等三角點 (附樁)', 'trig_2nd_IC': '二等三角點 (附樁)', 'trig_2nd_MC': '二等三角點 (中心樁)',
 'trig_2nd_CS': '二等三角點 (副點)', 'trig_2nd_RE': '二等三角點 (重測)', 'trig_3rd_IC': '三等三角點 (附樁)',
 'trig_3rd_MC': '三等三角點 (中心樁)', 'trig_3rd_CS': '三等三角點 (副點)', 'trig_3rd_RE': '三等三角點 (重測)',
 'forest_trig': '森林三角點', 'forest_trig_aero': '森林三角點 (航測)', 'forest_suppl': '森林補點',
 'forest_boundary': '林班界點', 'forest_monopoly': '專賣局基點', 'forest_mountain': '高山基點',
 'forest_aero': '林務航測點', 'forest_exfo': '舊林務基點', 'forest_exfo_JP': '日治林務基點',
 'farm_bureau': '農林基點', 'farm_hengchun': '恆春農場基點',
 'navy_trig_JP': '海軍三角點 (日治)', 'navy_trig_TW': '海軍三角點 (戰後)',
 'navy_suppl_JP': '海軍補點 (日治)', 'navy_suppl_TW': '海軍補點 (戰後)',
 'water_trig': '水利三角點', 'water_suppl': '水利補點', 'water_boundary': '水利界點',
 'water_reservoir': '水庫基點', 'water_taipei': '台北水利基點', 'hsinchu_water': '新竹水利基點',
 'bm_japan1': '日治水準點 (一型)', 'bm_japan2': '日治水準點 (二型)', 'bm_old': '舊水準點',
 'bm_new': '新水準點', 'bm_cgs': '內政部水準點', 'bm_water': '水利水準點', 'bm_reservoir': '水庫水準點',
 'bm_port': '港務水準點', 'bm_taipower': '台電水準點', 'bm_sinica': '中研院水準點',
 'bm_aero': '航測水準點', 'bm_tainan': '台南水準點', 'bm_kaohsiung': '高雄水準點',
 'suppl': '圖根補點', 'suppl_GO': '補點 (政府)', 'suppl_IA': '補點 (航測)', 'suppl_FB': '補點 (林務)',
 'suppl_TW': '補點 (戰後)', 'suppl_UN': '補點 (不明)', 'suppl_CS': '補點 (副點)',
 'suppl_port': '補點 (港務)', 'suppl_taipower': '補點 (台電)', 'suppl_eclipse': '補點 (日食觀測)',
 'suppl_declination': '補點 (磁偏觀測)',
 'satellite_gps': '衛星控制點 (GPS)', 'satellite_1st': '一等衛星控制點', 'satellite_2nd': '二等衛星控制點',
 'satellite_3rd': '三等衛星控制點', 'satellite_4th': '四等衛星控制點',
 'polygon_IT': '圖根多邊點 (義式)', 'polygon_TW': '圖根多邊點 (戰後)',
 'mine': '礦業基點', 'mine_suppl': '礦業補點', 'replica': '仿置基石', 'sectionstake': '段界樁',
 'riverbank': '河岸點', 'taiwanp': '臺灣省精測點', 'taipei': '台北圖根點', 'taipeic': '台北市圖根點',
 'taipei_tax': '台北稅務基點', 'keelung': '基隆圖根點', 'penghu': '澎湖圖根點', 'kinmen': '金門圖根點',
 'miaoli': '苗栗圖根點', 'miaoli_old': '苗栗舊圖根點', 'kaohsiung': '高雄圖根點',
 'military_zone': '軍事管制基點', 'military_zone_A': '軍事管制基點 (甲)', 'sinica_trig': '中研院三角點',
}

# (value, key) -> 中文, overriding TR where the same value means different things per key.
CTX = {
 ('yes', 'mountain_pass'): '埡口', ('yes', 'shelter'): '有遮蔽所', ('yes', 'via_ferrata_flag'): '攀岩鐵道',
 ('no', 'shelter'): '無遮蔽所', ('set', 'via_ferrata_flag'): '攀岩鐵道',
 ('station', 'power'): '變電所', ('station', 'railway'): '車站', ('station', 'amenity'): '車站',
 ('station', 'information'): '車站', ('station', 'shop'): '車站', ('station', 'tourism'): '車站',
 ('tower', 'power'): '高壓電塔', ('tower', 'man_made'): '塔',
 ('line', 'power'): '高壓電線', ('plant', 'power'): '發電廠', ('generator', 'power'): '發電機組',
 ('camp_site', 'landuse'): '營地用地', ('ruins', 'building'): '廢墟 (建物)', ('castle', 'building'): '城堡 (建物)',
 ('hotel', 'building'): '旅館 (建物)', ('hostel', 'building'): '青年旅館 (建物)', ('embassy', 'building'): '大使館 (建物)',
 ('museum', 'building'): '博物館 (建物)', ('library', 'building'): '圖書館 (建物)', ('city_wall', 'building'): '城牆 (建物)',
 ('wall', 'building'): '牆 (建物)', ('military', 'landuse'): '軍事用地',
 ('disused', 'highway'): '停用道路', ('disused', 'railway'): '停用鐵路',
 ('abandoned', 'highway'): '廢棄道路', ('abandoned', 'railway'): '廢棄鐵路',
 ('construction', 'highway'): '施工中道路', ('construction', 'landuse'): '施工區', ('construction', 'railway'): '施工中鐵路',
 ('crossing', 'railway'): '平交道', ('platform', 'railway'): '月台', ('platform', 'highway'): '月台',
 ('residential', 'landuse'): '住宅區', ('railway', 'landuse'): '鐵路用地', ('zoo', 'landuse'): '動物園用地',
 ('forest', 'landuse'): '林地', ('grass', 'landuse'): '草地', ('grassland', 'landuse'): '草原',
 ('meadow', 'landuse'): '牧草地', ('cemetery', 'landuse'): '墓地用地', ('common', 'landuse'): '公共綠地',
 ('garden', 'leisure'): '花園',
 ('golf_course', 'sport'): '高爾夫', ('golf', 'sport'): '高爾夫', ('swimming', 'sport'): '游泳',
 ('shooting', 'sport'): '射擊', ('soccer', 'sport'): '足球', ('tennis', 'sport'): '網球',
 ('stadium', 'sport'): '體育場', ('sports_centre', 'sport'): '運動中心', ('water_park', 'sport'): '水上樂園',
 ('climbing', 'climbing'): '攀岩', ('cl_yes', 'climbing'): '可攀岩',
 ('water_point', 'waterway'): '取水點', ('drinking_water', 'waterway'): '飲水點',
 ('rapids', 'waterway'): '急流', ('waterfall', 'waterway'): '瀑布', ('forest', 'natural'): '森林',
 ('funicular', 'highway'): '纜索鐵道', ('light_rail', 'highway'): '輕軌', ('subway', 'highway'): '地鐵',
 ('tram', 'highway'): '路面電車', ('narrow_gauge', 'highway'): '窄軌鐵路', ('miniature', 'highway'): '迷你鐵道',
 ('preserved', 'highway'): '保存鐵道', ('bus_guideway', 'railway'): '導軌公車',
 ('light_rail', 'station'): '輕軌站', ('subway', 'station'): '地鐵站', ('tram_stop', 'station'): '電車站',
 ('ferry', 'aerialway'): '渡輪', ('ferry', 'route'): '渡輪航線',
 ('cable_car', 'route'): '纜車路線', ('gondola', 'route'): '纜車路線',
 ('picnic_site', 'landuse'): '野餐區用地', ('caravan_site', 'landuse'): '露營車營地用地',
 ('recreation_ground', 'leisure'): '遊憩場', ('recreation_ground', 'tourism'): '遊憩區',
}

KEY_ZH = {
 'natural': '自然', 'tourism': '觀光', 'amenity': '設施', 'highway': '道路', 'waterway': '水道',
 'man_made': '人造物', 'historic': '歷史', 'boundary': '界線', 'leisure': '休閒', 'landuse': '土地利用',
 'railway': '鐵路', 'aerialway': '空中纜道', 'aeroway': '航空', 'power': '電力', 'barrier': '障礙物',
 'shop': '商店', 'religion': '宗教', 'information': '資訊', 'place': '聚落', 'sport': '運動',
 'memorial': '紀念物', 'emergency': '緊急', 'mountain_pass': '山口', 'survey_point': '測量點',
 'building': '建物', 'military': '軍事', 'route': '路線', 'ford': '渡河', 'ladder': '梯',
 'rungs': '踏階', 'safety_rope': '安全繩', 'via_ferrata_flag': '攀岩鐵道', 'shelter': '遮蔽',
 'shelter_type': '遮蔽類型', 'generator:source': '發電來源', 'piste:type': '滑雪道', 'tower': '塔',
 'station': '車站', 'climbing': '攀岩', 'vending': '販賣機', 'cycle_node': '自行車節點',
 'checkpoint:type': '檢查點',
}


def local(tag):
    return tag.split('}')[-1]


def pick_svg(value, svgs):
    """Choose the icon best matching value: exact `s_/p_<value>.svg`, else a containing one, else
    the first in document order (the generic icon usually precedes nested variants)."""
    if not svgs:
        return None
    for prefix in ('s_', 'p_', 's_gpx_'):
        cand = f'{prefix}{value}.svg'
        if cand in svgs:
            return cand
    containing = [s for s in svgs if value in s]
    if containing:
        return min(containing, key=len)
    return svgs[0]


def extract(theme_path):
    """Walk the theme; return ({(value, key)}, {(value, key): svg-or-None})."""
    res = set()
    raw = {}  # (value, key) -> ordered unique list of svgs found under the rule

    def visit(el):
        tag = local(el.tag)
        svgs = []
        if tag == 'symbol':
            src = el.get('src') or ''
            if src.endswith('.svg'):
                svgs.append(src.split('/')[-1])
        rendered = tag in RENDER
        child = False
        for c in list(el):
            r, s = visit(c)
            if r:
                child = True
            for x in s:
                if x not in svgs:
                    svgs.append(x)
        if tag == 'rule' and (rendered or child):
            k = el.get('k'); v = el.get('v')
            if k and v:
                for kk in k.split('|'):
                    if kk in FEATURE:
                        for vv in v.split('|'):
                            if vv not in SKIP_VAL:
                                res.add((vv, kk))
                                lst = raw.setdefault((vv, kk), [])
                                for x in svgs:
                                    if x not in lst:
                                        lst.append(x)
        return (rendered or child, svgs)

    visit(ET.parse(theme_path).getroot())
    svgmap = {key: pick_svg(key[0], lst) for key, lst in raw.items()}
    return res, svgmap


def parse_version(theme_path):
    with open(theme_path, encoding='utf-8') as f:
        text = f.read()
    m = re.search(r'v(\d{4}\.\d{2}\.\d{2})', text)
    return m.group(1) if m else 'unknown'


def zh(v, k):
    if (v, k) in CTX:
        return CTX[(v, k)]
    return TR.get(v)


def download_theme(force):
    """Fetch the theme bundle into mapdata/. Returns 'unchanged' if the server reports 304."""
    os.makedirs(MAPDATA, exist_ok=True)
    meta = {}
    if os.path.exists(META):
        try:
            meta = json.load(open(META))
        except Exception:
            meta = {}
    last_err = None
    for base in MIRRORS:
        url = base + THEME_ZIP
        req = urllib.request.Request(url, headers={'User-Agent': 'jiudge-gen/1.0'})
        if not force and meta.get('etag'):
            req.add_header('If-None-Match', meta['etag'])
        if not force and meta.get('last_modified'):
            req.add_header('If-Modified-Since', meta['last_modified'])
        try:
            print(f'下載: {url}')
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
                etag = resp.headers.get('ETag')
                last_mod = resp.headers.get('Last-Modified')
        except urllib.error.HTTPError as e:
            if e.code == 304:
                print('  伺服器回 304 (未變更)')
                return 'unchanged'
            last_err = e
            print(f'  失敗: HTTP {e.code}')
            continue
        except Exception as e:
            last_err = e
            print(f'  失敗: {e}')
            continue
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            z.extractall(MAPDATA)
        json.dump({'etag': etag, 'last_modified': last_mod, 'url': url},
                  open(META, 'w'))
        print(f'  完成, 解壓到 {MAPDATA}')
        return 'downloaded'
    raise SystemExit(f'所有 mirror 下載失敗: {last_err}')


def existing_version():
    if not os.path.exists(JSON_OUT):
        return None
    try:
        return json.load(open(JSON_OUT)).get('version')
    except Exception:
        return None


def generate(version):
    res, svgmap = extract(THEME)
    rows = sorted(res, key=lambda x: (x[0].lower(), x[1]))
    missing = sorted({v for v, k in res if zh(v, k) is None})

    # --- docs/map-symbols.md ---
    lines = [
        '# 地圖符號對照表', '',
        f'**地圖資料版本: v{version}** (RudyMap MOI.OSM Taiwan TOPO)', '',
        '本表列出渲染主題 `MOI_OSM.xml` 會在地圖上畫出的所有物件, 以及對應的 OSM 標籤與中文意義. '
        '供 "?" 符號辨識功能與使用者查閱之用. 由 `scripts/gen_symbols.py` 自動產生, 請勿手動編輯.', '',
        '- 排序: 先依 `value` (英文內容), 再依 `key`. 中文翻譯不參與排序.',
        '- 同一個 `value` 常掛在多個 `key` 下 (RudyMap 對 POI 的寬鬆比對), 因此會出現多列.',
        '- 測量基準點 (`survey_point`) 子類眾多, 中文為盡力翻譯, 專業名稱可能需校訂.',
        '- 完整標籤為 `key=value`.', '',
        f'共 {len(rows)} 列, {len({v for v, k in res})} 種物件.', '',
        '| value | key | 中文 |', '|---|---|---|',
    ]
    for v, k in rows:
        lines.append(f'| `{v}` | `{k}` | {zh(v, k) or "(待補)"} |')
    lines.append('')
    os.makedirs(os.path.dirname(DOC), exist_ok=True)
    open(DOC, 'w', encoding='utf-8').write('\n'.join(lines))

    # --- app/src/main/assets/symbols.json ---
    symbols = {}
    for v, k in rows:
        name = zh(v, k)
        if name is None:
            continue
        symbols[f'{k}={v}'] = {'name': name, 'svg': svgmap.get((v, k))}
    out = {
        'version': version,
        'generator': 'scripts/gen_symbols.py',
        'keyPriority': KEY_PRIORITY,
        'surface': SURFACE,
        'adminLevel': ADMIN_LEVEL,
        'symbols': symbols,
    }
    os.makedirs(os.path.dirname(JSON_OUT), exist_ok=True)
    with open(JSON_OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write('\n')

    print(f'產生 {DOC} ({len(rows)} 列)')
    print(f'產生 {JSON_OUT} ({len(symbols)} 個符號)')
    if missing:
        print('未翻譯:', ', '.join(missing))


def main():
    ap = argparse.ArgumentParser(description='Generate map-symbol reference + identify table.')
    ap.add_argument('--download', action='store_true', help='fetch latest theme from RudyMap mirrors')
    ap.add_argument('--force', action='store_true', help='regenerate even if the version is unchanged')
    args = ap.parse_args()

    if args.download:
        download_theme(args.force)
    if not os.path.exists(THEME):
        raise SystemExit(f'找不到主題 {THEME}; 請先執行 --download')

    version = parse_version(THEME)
    old = existing_version()
    if version == old and not args.force:
        print(f'版本未變 (v{version}), 略過產生. 如改了翻譯邏輯請加 --force.')
        return
    if old and version != old:
        print(f'版本變更: v{old} -> v{version}')
    generate(version)


if __name__ == '__main__':
    main()
