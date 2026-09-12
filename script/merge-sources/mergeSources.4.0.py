#! /usr/bin/env python3
# pip install deepmerge charset-normalizer requests
from deepmerge import Merger
import datetime
import json
import sys
import re
import os
import requests
from pathlib import Path
from urllib.parse import urljoin  # [新增] 用于标准路径拼接
from concurrent.futures import ThreadPoolExecutor  # [4.0] 并行抓取链接内容
from progress import Progress, detail, normalize_reason, detail_path, logs_dir  # [日志] 进度/ETA/详情分层

from charset_normalizer import from_bytes


# 调试常量
DEBUG_MODE = True

# 定义常量
INPUT_FILE_PATH = "input.txt"
OUTPUT_FILE_PATH = "output.txt"
# ================= [新增] 定义默认m3u输出文件名 =================
DEFAULT_OUTPUT_M3U_FILE = "output-m3u.txt"
# ================= [新增] 定义默认txt输出文件名 =================
DEFAULT_OUTPUT_TXT_FILE = "output-txt.txt"
# ================= [新增] 定义默认覆盖文件名 =================
DEFAULT_OVERRIDE_FILE = "override.json"

# ================= [新增] 定义 URL 替换映射 =================
URL_REPLACEMENTS = [
    {
        "old": r".*https://raw\.githubusercontent\.com",
        "new": "https://rawgithubusercontent.cnfaq.cn"
    }
]
# =========================================================

# ================= [4.0] 并行抓取链接内容的工作线程数 =================
FETCH_WORKERS = 20
# =========================================================

# ================= [4.1] 校验lives 转换（联网）的并发数与超时 =================
# convert_to_group_format() 对 .m3u/.txt 源会发起网络请求；原先在主循环里**串行**逐个等待，
# 实测 152 个元素耗时 4分49秒，是 mergeSources 内部最大瓶颈。改为并发后按"元素数/并发数"收敛。
CONVERT_WORKERS = 20
# 转换抓取专用超时：需要**下载完整 m3u/txt 源**，不同于 urlcheck 那种"只探测连通性"的 3/5，
# 因此取值更宽松；且只作用于 convert_to_group_format()，不影响主抓取阶段
# （主抓取仍用 get_url_content 的默认 10s）。
CONVERT_CONNECT_TIMEOUT = 10   # 连接超时(秒)
CONVERT_READ_TIMEOUT = 30      # 读取超时(秒)，按"两次数据包间隔"计，非总时长
# =====================================================================

# ================= [4.1] lives 内容过滤（空分组 / 分组黑名单 / 域名黑名单） =================
# 说明：过滤发生在 lives 合并之后、输出 tv.txt / tv.m3u 之前；
#       被过滤的内容会按类型落盘为**可直接复用的 tv.txt / tv.m3u**（目录与运行日志相同）。

# 空分组规则开关：为 True 时丢弃 group 为空（或为占位值）的分组。
# 依据样本：web/tv.txt 中"未分组"桶占 39940 个频道 / 74.7% 体积，是脏数据最集中的地方。
FILTER_EMPTY_GROUP = True
# 被视为"空分组"的取值（不区分大小写；空串表示 group 缺失或纯空白）
FILTER_EMPTY_GROUP_VALUES = ['', '未分组', 'undefined', 'null', 'none']

# 分组黑名单：命中（子串匹配，不区分大小写）则整组丢弃。
# 依据样本统计，以下分组的内容为成人向（合计约 9321 个频道 / 11.9% 体积）：
#   Adultos(4448)  私密169169(2878)  头条(1219)  麻豆(431)  VIPxjvip(277)  性世界(50)
FILTER_GROUP_BLACKLIST = [
    'Adultos',
    '麻豆',
    '私密169169',
    '头条',
    'VIPxjvip',
    '性世界',
]

# 域名黑名单：URL 命中（子串匹配，不区分大小写）则丢弃该 URL。
# 依据样本统计（web/tv.txt，共 61386 个 URL），以下域名承载约 81% 的 URL，
# 且其归属分组均为成人向，故按"主域名"书写以覆盖其所有子域：
#   cdn2020.com    43570 个  (t33/t26 等子域，归属 未分组/私密169169/麻豆/头条/乌鸦)
#   vrpro.fun       4516 个  (mp4 点播，归属 Adultos 系列分组)
#   97img.com       1413 个  (t0.97img.com，归属 头条/麻豆/性世界)
#   imgstream2.com   343 个  (v.imgstream2.com，归属 私密169169)
FILTER_DOMAIN_BLACKLIST = [
    'cdn2020.com',
    'vrpro.fun',
    '97img.com',
    'imgstream2.com',
]

# 被过滤内容的落盘目录：None 表示与运行日志同目录（<cwd>/logs）
FILTER_OUTPUT_DIR = None
# =====================================================================================

# ================= [4.1] iptv-org 公开直播源合并 =================
# 说明：抓取后解析为 lives（group/channels）格式，并入 validate_lives 的 valid_lives，
#       与原有产出复用同一套 merge_lives_groups() 完成"同组同频道合并 + URL 去重"；
#       随后一并写入 tv.txt / tv.m3u，因此会自动参与后续的 prune_streams 可用性校验。
IPTV_ORG_ENABLED = True                 # 总开关（False 时完全跳过）
IPTV_ORG_M3U_URL = "https://iptv-org.github.io/iptv/index.m3u"
# 备选地址（想换源时，把上面一行整体替换为其中之一即可）：
#   https://iptv-org.github.io/iptv/countries/cn.m3u     # 仅中国频道（约 28KB）
#   https://iptv-org.github.io/iptv/languages/zho.m3u    # 仅中文频道（约 43KB）
# 体积参考（实测 Content-Length）：index.m3u 约 2.5MB / cn.m3u 约 28KB / zho.m3u 约 43KB
IPTV_ORG_FETCH_TIMEOUT = 60             # 抓取超时(秒)：index.m3u 约 2.5MB，默认 10s 不够
# ================================================================

# ================= [新增] 定义多余字段列表 =================
EXTRA_FIELDS = [
    'flags', 'warningText', 'doh', 'logo', 'urls', 'notice',
    'disabled_wallpaper', 'storeHouse', 'code', 'msg', 'page', 'pagecount',
    'limit', 'total', 'list', 'class', 'iptv', 'channel', 'drive', 'analyze',
    'setting', 'analyzeHistory', 'history', 'searchHistory', 'star', 'homepage',
    'homeLogo', 'adblock', 'recommend', 'rating', 'pullWord', 'subtitle'
]
# =========================================================

# ================= [新增] 定义sites必需字段列表 =================
SITES_REQUIRED_FIELDS = ['key', 'name', 'api', 'type']
# =========================================================

# 定义用于判断单仓/多仓的特征字段列表
SINGLE_CANG_FIELDS = {'video', 'spider', 'sites', 'iptv', 'channel', 'analyze', 'lives', 'parses'}

# 定义频道聚合排除关键字
CHANNEL_AGGREGATION_EXCLUDE_KEYWORDS = ['第']

# 定义频道名清洗关键字
CHANNEL_NAME_CLEAN_KEYWORDS = ['-']

# 定义分组名清洗关键字
GROUP_NAME_CLEAN_KEYWORDS = ['频道', '丨', '｜', '·', '-', '_', ';', '.', '📺', '☘️'
, '🏀', '🏛', '🎬', '🪁', '🇨🇳', '👠', '💋', '💃', '💝', '💖', '🍱', '🛰', '🔥', '🤹🏼'
, '🎼', '📛', '🐷', '🐻', '💰', '🎵', '🎮', '📡', '🕘️', '📢', '🎞', '🌊', '🇭🇰', '🇹🇼'
, '🇰🇷', '🎰', '🇯🇵', '📻', '🇺🇸', '🙏', '🌏', '🖥', '📽', '🔥', '🐬', '💰', '🆕']

# 调试输出文件名
DEBUG_ORIGINAL_LIVES_FILE = 'debug_original_lives.json'
DEBUG_VALID_LIVES_FILE = 'debug_valid_lives.json'

def remove_comments_from_string(input_string):
    input_string = re.sub(r'^[ ]*//[^\n]*', '', input_string, flags=re.MULTILINE)
    input_string = re.sub(r'^[ ]*#[^\n]*', '', input_string, flags=re.MULTILINE)
    input_string = re.sub(r'^[ ]*/\*.*?\*/', '', input_string, flags=re.DOTALL)
    return input_string

def preprocess_url(url):
    """
    预处理URL，根据URL_REPLACEMENTS进行替换
    :param url: 原始URL
    :return: 替换后的URL
    """
    processed_url = url
    for replacement in URL_REPLACEMENTS:
        if replacement["old"] in processed_url:
            old_url = processed_url
            processed_url = processed_url.replace(replacement["old"], replacement["new"])
            print(f"  [URL Replace] {old_url} -> {processed_url}")
    return processed_url


def is_json(content):
    try:
        json.loads(content)
    except ValueError:
        return False
    return True

def detect_encoding(byte_data):
    """
    检测字节流的编码 (使用 charset-normalizer)
    :param byte_data: bytes
    :return: str 编码名称
    """
    if not byte_data:
        return 'utf-8'

    # 使用 charset-normalizer 进行检测
    result = from_bytes(byte_data).best()

    # 如果检测到结果，直接使用其编码；否则默认 utf-8
    if result:
        return result.encoding
    return 'utf-8'

def decode_safely(byte_data):
    """
    安全解码字节流为字符串
    :param byte_data: bytes
    :return: str or None
    """
    if not byte_data:
        return None

    # 1. 简单的二进制文件检查 (例如 PNG header 0x89504E47, JPEG header 0xFFD8FF)
    # 如果是图片等明显的二进制，直接返回 None
    if len(byte_data) > 4:
        header = byte_data[:4]
        # PNG, JPEG, GIF, PDF, ZIP 等常见二进制头
        binary_headers = [
            b'\x89PNG', b'\xff\xd8\xff', b'GIF8', b'%PDF', b'PK\x03\x04'
        ]
        for bh in binary_headers:
            if header.startswith(bh):
                detail("  [Skip] 检测到二进制文件头，跳过解码。")
                return None

    encoding = detect_encoding(byte_data)

    try:
        # 尝试用检测到的编码解码
        return byte_data.decode(encoding)
    except (UnicodeDecodeError, LookupError):
        try:
            # 失败则尝试 UTF-8 容错
            return byte_data.decode('utf-8', errors='replace')
        except Exception:
            return None

def get_local_file_content(file_path):
    try:
        # 以二进制模式读取
        with open(file_path, 'rb') as file:
            byte_content = file.read()
        detail(f"Read local file: {file_path}")

        # 解码
        content = decode_safely(byte_content)
        return content
    except Exception as e:
        print(f"Error reading local file {file_path}: {e}")
        return None

def get_url_content(url, timeout=(10, 10)):   # timeout 可为 int，或 (连接超时, 读取超时) 元组
    try:
        # 预处理 URL
        processed_url = preprocess_url(url)
        
        response = requests.get(processed_url, timeout=timeout)
        response.raise_for_status()

        byte_content = response.content

        # 检查 HTTP Content-Type，过滤掉明显的非文本
        content_type = response.headers.get('Content-Type', '').lower()
        skip_types = ['image/', 'video/', 'audio/', 'application/octet-stream', 'application/pdf', 'application/zip']
        if any(t in content_type for t in skip_types):
            detail(f"  [Skip] URL Content-Type 为非文本类型: {content_type}")
            return None

        detail(f"Fetched URL: {url}")

        # 解码
        content = decode_safely(byte_content)
        return content
    except requests.Timeout as e:
        detail(f"Request timed out for URL {url}: {e}")
        return None
    except requests.RequestException as e:
        detail(f"Error fetching URL {url} [reason={normalize_reason(e)}]: {e}")
        return None

def append_to_file_unique(file_path, line, existing_lines=None):
    """
    向文件中添加唯一行
    :param file_path: 文件路径
    :param line: 要添加的行
    :param existing_lines: 已存在的行集合（可选）
    """
    p = Path(file_path)
    
    # 如果没有提供 existing_lines，则读取文件
    if existing_lines is None:
        existing_lines = set()
        if p.exists():
            try:
                with open(p, 'r', encoding='utf-8') as f:
                    for l in f:
                        stripped = l.strip()
                        if stripped:
                            existing_lines.add(stripped)
            except Exception as e:
                print(f"Warning: Could not read history file {file_path}: {e}")
    
    if line not in existing_lines:
        try:
            with open(p, 'a', encoding='utf-8') as f:
                f.write(line + '\n')
            detail(f"Appended to file: {line} -> {file_path.name}")
        except Exception as e:
            print(f"Error writing to file {file_path}: {e}")

def write_list_to_file(file_path, lines):
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            for line in lines:
                f.write(line + '\n')
        print(f"Written list to: {file_path}")
    except Exception as e:
        print(f"Error writing list file {file_path}: {e}")

def replace_file(source_path, target_path):
    try:
        src = Path(source_path)
        tgt = Path(target_path)
        if src.exists():
            src.replace(tgt)
            print(f"Replaced original input file {target_path} with valid list.")
    except Exception as e:
        print(f"Error replacing file: {e}")

def is_single_cang(parsed_json):
    """
    判断是否为单仓
    """
    if not isinstance(parsed_json, dict):
        return False

    top_level_keys = set(parsed_json.keys())
    if top_level_keys & SINGLE_CANG_FIELDS:
        return True

    return False

def extract_urls_deep(obj):
    """
    深度遍历 JSON 对象，提取所有以 http(s):// 开头的字符串
    """
    urls = []
    if isinstance(obj, str):
        if obj.startswith(('http://', 'https://')):
            urls.append(obj)
    elif isinstance(obj, dict):
        for value in obj.values():
            urls.extend(extract_urls_deep(value))
    elif isinstance(obj, list):
        for item in obj:
            urls.extend(extract_urls_deep(item))
    return urls

def fetch_and_parse_single_cang(url):
    """
    尝试获取一个 URL 并将其解析为单仓数据
    """
    detail(f"  [Multi->Single] Fetching sub-url: {url}")
    content = None

    if url.startswith('/') or url.startswith('.'):
        content = get_local_file_content(url)
    elif url.startswith('http'):
        content = get_url_content(url)
    else:
        return None

    if content is None:
        return None

    # 清洗
    content = remove_comments_from_string(content)
    content = content.replace("\n", "").replace("\r", "")

    if not is_json(content):
        return None

    try:
        parsed = json.loads(content)
        if isinstance(parsed, dict):
            return parsed
        else:
            return None
    except:
        return None

def process_input_file(input_file_path=INPUT_FILE_PATH):
    """
    处理输入文件
    """
    raw_data_map = {} # 存储原始数据: url -> data
    valid_sources = []
    invalid_sources = []

    try:
        with open(input_file_path, 'r', encoding='utf-8') as input_file:
            raw_lines = input_file.readlines()
    except FileNotFoundError:
        print(f"The file {input_file_path} was not found.")
        return {}, [], []
    except Exception as e:
        print(f"An error occurred while processing the file: {e}")
        return {}, [], []

    # 分类收集待抓取目标
    targets = []  # (trimmed_line, kind)
    for raw in raw_lines:
        trimmed_line = raw.strip()
        if not trimmed_line:
            continue
        if trimmed_line.startswith('/') or trimmed_line.startswith('.'):
            targets.append((trimmed_line, 'local'))
        elif trimmed_line.startswith('http'):
            targets.append((trimmed_line, 'http'))
        else:
            print("Line does not start with '/' or 'http', skipping.")
            invalid_sources.append(trimmed_line)

    # [4.0] 并行抓取内容（抓取为网络 I/O，使用线程池并发；抓取本身保持原逻辑不变）
    def _fetch(item):
        line, kind = item
        res = get_local_file_content(line) if kind == 'local' else get_url_content(line)
        fetch_pg.update(1)
        return line, res

    fetch_pg = Progress(len(targets), prefix="[mergeSources] 抓取输入源", interval=2.0)
    contents = {}
    if targets:
        with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as ex:
            for line, content in ex.map(_fetch, targets):
                contents[line] = content
    fetch_pg.finish()

    # 串行处理结果（与 3.0 完全一致：清洗 / 注释去除 / JSON 校验 / 解析）
    parse_pg = Progress(len(targets), prefix="[mergeSources] 解析输入源", interval=2.0)
    for trimmed_line, _kind in targets:
        content = contents.get(trimmed_line)

        if content is not None:
            content = remove_comments_from_string(content)
            content = content.replace("\n", "").replace("\r", "")

        if content is not None and is_json(content):
            try:
                parsed_dict = json.loads(content)
                raw_data_map[trimmed_line] = parsed_dict
                valid_sources.append(trimmed_line)
            except Exception as e:
                detail(f"JSON 解析失败: {e}")
                invalid_sources.append(trimmed_line)
        else:
            invalid_sources.append(trimmed_line)
        parse_pg.update(1)
    parse_pg.finish()
    print(f"[mergeSources] 输入源解析完成: 有效 {len(valid_sources)} / 无效 {len(invalid_sources)}")

    return raw_data_map, valid_sources, invalid_sources


def custom_list_merge(merger, path, list1, list2):
    if all(isinstance(item, dict) for item in list1 + list2):
        key_id_dict = {}
        identifier_fields = ['key', 'id', 'name']

        for item in list1:
            identifier = next((item.get(field) for field in identifier_fields if item.get(field)), None)
            if identifier is not None:
                key_id_dict[identifier] = item
            else:
                key_id_dict[id(item)] = item

        for item in list2:
            identifier = next((item.get(field) for field in identifier_fields if item.get(field)), None)
            if identifier is not None:
                if identifier in key_id_dict:
                    key_id_dict[identifier].update(item)
                else:
                    key_id_dict[identifier] = item
            else:
                key_id_dict[id(item)] = item

        merged_list = list(key_id_dict.values())
        return merged_list
    else:
        unique_items = set(list1).union(set(list2))
        return list(unique_items)

custom_merger = Merger(
    [
     (list, custom_list_merge),
     (set, "union"),
     (tuple, "concat"),
     (dict, "merge"),
    ],
    ["override"],
    ["override"]
)

def merge_dicts(dicts_list):
    merged_dict = {}
    for d in dicts_list:
        merged_dict = custom_merger.merge(merged_dict, d)
    return merged_dict

def validate_lives_element(element):
    """
    验证单个 lives 元素是否符合内置频道模式的合法结构
    确保不会导致 loadLives 方法异常中断
    :param element: lives 数组中的单个元素
    :return: bool - 是否合法
    """
    # 检查元素是否为字典
    if not isinstance(element, dict):
        detail("  [Validate] 跳过：非字典元素")
        return False
    
    # 检查是否包含必要字段
    if 'group' not in element:
        detail("  [Validate] 跳过：缺少 group 字段")
        return False
    
    if 'channels' not in element:
        detail("  [Validate] 跳过：缺少 channels 字段")
        return False
    
    # 检查 group 字段是否为非空字符串
    if not isinstance(element['group'], str) or not element['group'].strip():
        detail("  [Validate] 跳过：group 字段为空或非字符串")
        return False
    
    # 检查 channels 字段是否为数组
    if not isinstance(element['channels'], list):
        detail("  [Validate] 跳过：channels 字段非数组")
        return False
    
    # 检查 channels 数组是否为空
    if not element['channels']:
        detail("  [Validate] 跳过：channels 数组为空")
        return False
    
    # 检查是否包含 proxy://，如果包含则视为无效
    element_str = json.dumps(element)
    if 'proxy://' in element_str:
        detail("  [Validate] 跳过：包含 proxy://")
        return False
    
    # 检查每个 channel 元素
    valid_channels = []
    for channel in element['channels']:
        if isinstance(channel, dict) and 'name' in channel and 'urls' in channel:
            if isinstance(channel['name'], str) and channel['name'].strip():
                if isinstance(channel['urls'], list) and channel['urls']:
                    # 检查 urls 数组元素是否为字符串
                    valid_urls = []
                    for url in channel['urls']:
                        if isinstance(url, str) and url.strip():
                            valid_urls.append(url)
                    if valid_urls:
                        channel['urls'] = valid_urls
                        valid_channels.append(channel)
    
    if not valid_channels:
        detail("  [Validate] 跳过：channels 数组中无合法频道")
        return False
    
    # 更新为验证后的 channels
    element['channels'] = valid_channels
    return True


def parse_m3u_content(content):
    """
    解析m3u格式内容
    :param content: m3u文件内容
    :return: 转换后的group格式列表
    """
    try:
        groups = {}
        lines = content.strip().split('\n')
        current_group = '未分组'
        current_channel = None
        
        for i, line in enumerate(lines):
            line = line.strip()
            if not line:
                continue
            
            if line.startswith('#EXTINF'):
                # 提取分组和频道名
                group_match = re.search(r'group-title="([^"]*)"', line)
                if group_match:
                    current_group = group_match.group(1)
                
                # 提取频道名
                name_match = re.search(r',(.+)$', line)
                if name_match:
                    current_channel = name_match.group(1).strip()
                
            elif line.startswith('http') and current_channel:
                # 添加URL到对应频道
                if current_group not in groups:
                    groups[current_group] = {}
                
                if current_channel not in groups[current_group]:
                    groups[current_group][current_channel] = []
                
                groups[current_group][current_channel].append(line)
                current_channel = None
        
        # 转换为group格式
        result = []
        for group_name, channels in groups.items():
            group_item = {
                'group': group_name,
                'channels': []
            }
            
            for channel_name, urls in channels.items():
                group_item['channels'].append({
                    'name': channel_name,
                    'urls': urls
                })
            
            result.append(group_item)
        
        return result
    except Exception as e:
        detail(f"[Convert] m3u解析失败: {e}")
        return None


def fetch_iptv_org_lives():
    """抓取 iptv-org/iptv 的公开 m3u，解析为 lives（group/channels）格式。

    注意：这里直接用 requests 取，而**不能**用 get_url_content()——后者会把
    Content-Type 含 'audio/' 的响应判为非文本并跳过，而本地址返回的正是
    audio/x-mpegurl（实测），用 get_url_content 会静默返回 None。

    解析结果与 convert_to_group_format() 结构一致，可直接并入 valid_lives，
    再交由 merge_lives_groups() 完成"同组同频道合并 + URL 去重"。

    :return: lives 列表；关闭开关 / 抓取失败 / 解析为空时返回 None
    """
    if not IPTV_ORG_ENABLED or not IPTV_ORG_M3U_URL:
        return None
    print(f"[IptvOrg] 开始抓取: {IPTV_ORG_M3U_URL}")
    try:
        resp = requests.get(IPTV_ORG_M3U_URL, timeout=IPTV_ORG_FETCH_TIMEOUT)
        resp.raise_for_status()
        content = resp.text
    except Exception as e:
        detail(f"[IptvOrg] 抓取失败: {e}")
        print(f"[IptvOrg] 抓取失败，跳过合并: {e}")
        return None

    lives = parse_m3u_content(content)
    if not lives:
        detail("[IptvOrg] m3u 解析结果为空")
        print("[IptvOrg] 解析结果为空，跳过合并")
        return None

    n_ch = sum(len(g.get('channels', []) or []) for g in lives)
    n_url = sum(len(ch.get('urls', []) or [])
                for g in lives for ch in (g.get('channels', []) or []))
    print(f"[IptvOrg] 解析完成：{len(lives)} 个分组 / {n_ch} 个频道 / {n_url} 个流地址")
    return lives


def parse_txt_content(content):
    """
    解析txt格式内容
    :param content: txt文件内容
    :return: 转换后的group格式列表
    """
    try:
        groups = {}
        lines = content.strip().split('\n')
        current_group = '未分组'
        
        for line in lines:
            line = line.strip()
            if not line:
                continue
            
            if line.endswith('#genre#'):
                # 提取分组名
                current_group = line.replace('#genre#', '').strip()
                # 去除可能存在的末尾逗号
                if current_group.endswith(','):
                    current_group = current_group[:-1].strip()
                if current_group not in groups:
                    groups[current_group] = {}
            else:
                # 提取频道名和URL
                # 只在第一个逗号处分割，处理URL中可能包含逗号的情况
                comma_index = line.find(',')
                if comma_index != -1:
                    channel_name = line[:comma_index].strip()
                    channel_urls_str = line[comma_index+1:].strip()
                    
                    if channel_name and channel_urls_str:
                        # 按 # 分割多个 URL
                        for url in channel_urls_str.split('#'):
                            url = url.strip()
                            if url and (url.startswith('http') or url.startswith('rtsp') or url.startswith('rtmp')):
                                if current_group not in groups:
                                    groups[current_group] = {}
                                
                                if channel_name not in groups[current_group]:
                                    groups[current_group][channel_name] = []
                                
                                if url not in groups[current_group][channel_name]:
                                    groups[current_group][channel_name].append(url)
        
        # 转换为group格式
        result = []
        for group_name, channels in groups.items():
            group_item = {
                'group': group_name,
                'channels': []
            }
            
            for channel_name, urls in channels.items():
                group_item['channels'].append({
                    'name': channel_name,
                    'urls': urls
                })
            
            result.append(group_item)
        
        return result
    except Exception as e:
        detail(f"[Convert] txt解析失败: {e}")
        return None



def convert_to_group_format(element):
    """
    将非合法的lives元素转换为合法的group格式
    :param element: lives数组中的单个元素
    :return: 转换后的group格式元素，转换失败返回None
    """
    if not isinstance(element, dict) or 'url' not in element:
        return None
    
    url = element.get('url', '').strip()
    if not url:
        return None
    
    # 检测URL类型
    url_lower = url.lower()
    
    if url_lower.endswith('.m3u'):
        # 处理m3u类型
        content = get_url_content(url, timeout=(CONVERT_CONNECT_TIMEOUT, CONVERT_READ_TIMEOUT))
        if content:
            return parse_m3u_content(content)
        return None
    
    elif url_lower.endswith('.txt'):
        # 处理txt类型，根据内容判断实际格式
        content = get_url_content(url, timeout=(CONVERT_CONNECT_TIMEOUT, CONVERT_READ_TIMEOUT))
        if content:
            # 根据内容特征判断是m3u还是txt格式
            if content.strip().startswith('#EXTM3U'):
                detail("[Convert] 检测到txt后缀的m3u格式内容")
                return parse_m3u_content(content)
            else:
                detail("[Convert] 检测到txt格式内容")
                return parse_txt_content(content)
        return None
    
    elif url_lower.endswith('.m3u8'):
        # 处理m3u8类型
        try:
            group_name = element.get('group', '其他').strip() or '其他'
            channel_name = element.get('name', '未知频道').strip() or '未知频道'
            
            # 构建简单的group格式
            result = [{
                'group': group_name,
                'channels': [{
                    'name': channel_name,
                    'urls': [url]
                }]
            }]
            
            return result
        except Exception as e:
            detail(f"[Convert] m3u8转换失败: {e}")
            return None
    
    return None

def get_most_frequent(stats_dict):
    """
    获取出现次数最多的键，当次数一样多时选择长度最短的键，当长度也一样时按照名称排序
    :param stats_dict: 统计字典 {键: 次数}
    :return: 出现次数最多的键
    """
    if not stats_dict:
        return '未分组'
    # 首先按次数排序，次数相同时按长度排序，长度相同时按名称排序
    return max(stats_dict.items(), key=lambda x: (x[1], -len(x[0]), x[0]))[0]

# ================= [4.1] 频道名清洗 =================
# 部分订阅源把 tvg 属性塞进了 name 字段，例如：
#   'tvgid="东南卫视" tvgname="东南卫视" tvglogo="..." grouptitle="卫视",东南卫视'
# 原样写出会导致：
#   - tv.txt 变成"三段式"（name 内含逗号），下游按第一个逗号切分的解析器会错位；
#   - tv.m3u 的 EXTINF 引号嵌套（tvg-name="tvgid="xxx" ..."），产物非法且体积膨胀。
_TVG_ATTR_RE = re.compile(r'(?:tvgid|tvgname|tvglogo|grouptitle)\s*=\s*"[^"]*"', re.I)


def clean_channel_name(name):
    """清洗频道名：剥离内嵌的 tvg 属性，并移除会破坏 tv.txt / tv.m3u 格式的逗号与双引号。"""
    if name is None:
        return '未命名'
    if not isinstance(name, str):
        name = str(name)
    raw = name.strip()
    if not raw:
        return '未命名'
    # 1) 剥离内嵌的 tvg 属性（tvgid / tvgname / tvglogo / grouptitle="..."）
    s = _TVG_ATTR_RE.sub(' ', raw)
    # 2) 若剥离后无有效内容（例如 name 只有 tvg 属性），退回原始值
    if not s.strip():
        s = raw
    # 3) 移除双引号（破坏 m3u 的 tvg-name="..."）与逗号（破坏 txt 按逗号切分）
    s = s.replace('"', ' ').replace(',', ' ')
    # 4) 折叠空白
    s = re.sub(r'\s+', ' ', s).strip()
    return s or '未命名'


# ================= [4.1] lives 内容过滤（空分组 / 分组黑名单 / 域名黑名单） =================

def _filter_output_dir():
    """被过滤内容的落盘目录（默认与运行日志同目录）。"""
    d = FILTER_OUTPUT_DIR if FILTER_OUTPUT_DIR else logs_dir()
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return d


def _is_empty_group(group):
    if group is None:
        return True
    g = str(group).strip().lower()
    return g in [str(v).strip().lower() for v in FILTER_EMPTY_GROUP_VALUES]


def _is_group_blacklisted(group):
    if not FILTER_GROUP_BLACKLIST or group is None:
        return False
    g = str(group).lower()
    for kw in FILTER_GROUP_BLACKLIST:
        if kw and str(kw).lower() in g:
            return True
    return False


def _is_domain_blacklisted(url):
    if not FILTER_DOMAIN_BLACKLIST or not url:
        return False
    u = str(url).lower()
    for d in FILTER_DOMAIN_BLACKLIST:
        if d and str(d).lower() in u:
            return True
    return False


def filter_lives(lives):
    """按"空分组规则 / 分组黑名单 / 域名黑名单"过滤 lives，并把被过滤内容按类型落盘。

    落盘为**可直接复用的 tv.txt / tv.m3u 格式**（不是日志 / 详情日志），目录与运行日志相同。
    :param lives: 合并后的 lives 数组
    :return: (过滤后的 lives, {过滤类型: 被过滤的分组列表})
    """
    if not isinstance(lives, list):
        return lives, {}

    kept = []
    buckets = {}      # reason -> [group dict, ...]

    def add(reason, group_item):
        buckets.setdefault(reason, []).append(group_item)

    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        group = group_item.get('group')

        # 1) 空分组规则
        if FILTER_EMPTY_GROUP and _is_empty_group(group):
            add('empty_group', group_item)
            continue

        # 2) 分组黑名单
        if _is_group_blacklisted(group):
            add('group_blacklist', group_item)
            continue

        # 3) 域名黑名单（URL 级：只丢弃命中的 URL，保留同频道其它地址）
        kept_channels = []
        dropped_channels = []
        for ch in group_item.get('channels', []) or []:
            if not isinstance(ch, dict):
                continue
            urls = ch.get('urls', []) or []
            good_urls = [u for u in urls if u and not _is_domain_blacklisted(u)]
            bad_urls = [u for u in urls if u and _is_domain_blacklisted(u)]
            if good_urls:
                new_ch = dict(ch)
                new_ch['urls'] = good_urls
                kept_channels.append(new_ch)
            if bad_urls:
                dropped_channels.append({'name': ch.get('name'), 'urls': bad_urls})
        if dropped_channels:
            add('domain_blacklist', {'group': group, 'channels': dropped_channels})
        if kept_channels:
            new_group = dict(group_item)
            new_group['channels'] = kept_channels
            kept.append(new_group)

    _write_filtered_buckets(buckets)
    return kept, buckets


def _write_filtered_buckets(buckets):
    """把被过滤的内容按类型写成可直接复用的 tv.txt / tv.m3u。"""
    d = _filter_output_dir()
    for reason, groups in buckets.items():
        if not groups:
            continue
        n_ch = sum(len(g.get('channels', []) or []) for g in groups)
        try:
            txt_path = os.path.join(d, f'filtered_{reason}.txt')
            m3u_path = os.path.join(d, f'filtered_{reason}.m3u')
            with open(txt_path, 'w', encoding='utf-8') as f:
                f.write(lives_to_txt(groups))
            with open(m3u_path, 'w', encoding='utf-8') as f:
                f.write(lives_to_m3u(groups))
            print(f"[Filter] {reason}: {len(groups)} 个分组 / {n_ch} 个频道 -> "
                  f"{txt_path}, {m3u_path}")
        except Exception as e:
            print(f"[Filter] {reason}: 落盘失败 {e}")


def lives_to_m3u(lives):
    """
    将 lives 数组转换为 m3u 格式
    :param lives: lives 数组
    :return: m3u 格式的字符串
    """
    if not isinstance(lives, list):
        return ""
    
    m3u_lines = ["#EXTM3U"]
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        
        # [4.1] 清洗分组名与频道名：剥离内嵌 tvg 属性、去除逗号与双引号
        group_name = clean_channel_name(group_item.get('group', '未分组'))
        channels = group_item.get('channels', [])
        
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            channel_name = clean_channel_name(channel_item.get('name', '未命名'))
            urls = channel_item.get('urls', [])
            
            for url in urls:
                if not url:
                    continue
                
                # 添加频道信息
                m3u_lines.append(f"#EXTINF:-1 tvg-name=\"{channel_name}\" group-title=\"{group_name}\",{channel_name}")
                m3u_lines.append(url)
    
    return "\n".join(m3u_lines)

def write_m3u_to_file(m3u_content, file_path):
    """
    将 m3u 内容写入文件
    :param m3u_content: m3u 格式的内容
    :param file_path: 文件路径
    """
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(m3u_content)
        print(f"M3U content written to: {file_path}")
    except Exception as e:
        print(f"Error writing M3U file {file_path}: {str(e)}")


def lives_to_txt(lives):
    """
    将 lives 数组转换为 TXT 格式
    :param lives: lives 数组
    :return: TXT 格式的字符串
    """
    txt_lines = []
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        
        # [4.1] 清洗分组名与频道名：剥离内嵌 tvg 属性、去除逗号与双引号
        group_name = clean_channel_name(group_item.get('group', '未分组'))
        channels = group_item.get('channels', [])
        
        # 添加分组定义
        txt_lines.append(f"{group_name},#genre#")
        
        # 添加频道定义
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            channel_name = clean_channel_name(channel_item.get('name', '未命名'))
            urls = channel_item.get('urls', [])
            
            # 将多个 URL 用 # 连接
            if urls:
                # 对每个 URL 中的 # 进行 URL encode 编码替换
                encoded_urls = []
                for url in urls:
                    # 只对 # 进行编码，保留其他字符
                    encoded_url = url.replace('#', '%23')
                    encoded_urls.append(encoded_url)
                urls_str = '#'.join(encoded_urls)
                txt_lines.append(f"{channel_name},{urls_str}")
        
        # 添加空行分隔不同分组
        txt_lines.append('')
    
    return '\n'.join(txt_lines)


def write_txt_to_file(txt_content, file_path):
    """
    将 txt 内容写入文件
    :param txt_content: txt 格式的内容
    :param file_path: 文件路径
    """
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(txt_content)
        print(f"TXT content written to: {file_path}")
    except Exception as e:
        print(f"Error writing TXT file {file_path}: {str(e)}")

def clean_string(s, keywords):
    """
    清理字符串，移除指定关键字
    :param s: 原始字符串
    :param keywords: 要移除的关键字列表
    :return: 清理后的字符串
    """
    if not isinstance(s, str):
        return s
    cleaned = s
    for keyword in keywords:
        cleaned = cleaned.replace(keyword, '')
    cleaned = cleaned.strip()
    return cleaned if cleaned else '未命名'

def should_exclude_from_aggregation(channel_name):
    """
    判断频道是否应排除在聚合之外
    :param channel_name: 频道名
    :return: 是否排除
    """
    if not isinstance(channel_name, str):
        return False
    # 检查是否为纯数字
    if channel_name.isdigit():
        return True
    # 检查是否包含排除关键字
    for keyword in CHANNEL_AGGREGATION_EXCLUDE_KEYWORDS:
        if keyword in channel_name:
            return True
    return False

def custom_channel_sort_key(channel_name):
    """
    自定义频道排序键，支持字符串排序和末尾数字排序
    :param channel_name: 频道名
    :return: 排序键
    """
    if not isinstance(channel_name, str):
        return (channel_name,)
    # 提取末尾的数字部分
    match = re.search(r'(\d+)$', channel_name)
    if match:
        # 分离字符串部分和数字部分
        str_part = channel_name[:match.start()]
        num_part = int(match.group(1))
        return (str_part, num_part)
    else:
        # 没有数字部分，直接返回字符串
        return (channel_name, 0)

def merge_lives_groups(lives):
    """
    合并 lives 数组中的重复分组和频道
    使用 URL 聚合并统计次数的算法
    :param lives: lives 数组
    :return: 合并后的 lives 数组
    """
    if not isinstance(lives, list):
        return []
    
    # 1. 按 URL 聚合并统计次数
    url_to_group_stats = {}  # URL -> {分组名: 出现次数}
    url_to_channel_stats = {}  # URL -> {频道名: 出现次数}
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        
        # 清洗分组名
        original_group_name = group_item.get('group', '未分组')
        cleaned_group_name = clean_string(original_group_name, GROUP_NAME_CLEAN_KEYWORDS)
        
        channels = group_item.get('channels', [])
        
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            original_channel_name = channel_item.get('name', '未命名')
            # 清洗频道名（对所有情况都生效）
            cleaned_channel_name = clean_string(original_channel_name, CHANNEL_NAME_CLEAN_KEYWORDS)
            
            urls = channel_item.get('urls', [])
            
            for url in urls:
                if not url:
                    continue
                
                # 更新分组统计
                if url not in url_to_group_stats:
                    url_to_group_stats[url] = {}
                # 对所有情况都使用清洗后的分组名
                url_to_group_stats[url][cleaned_group_name] = url_to_group_stats[url].get(cleaned_group_name, 0) + 1
                
                # 更新频道统计
                if url not in url_to_channel_stats:
                    url_to_channel_stats[url] = {}
                url_to_channel_stats[url][cleaned_channel_name] = url_to_channel_stats[url].get(cleaned_channel_name, 0) + 1
    
    # 2. 为每个 URL 选择出现次数最多的分组和频道
    url_to_best_match = {}
    for url, group_stats in url_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_stats = url_to_channel_stats.get(url, {})
        best_channel = get_most_frequent(channel_stats)
        url_to_best_match[url] = (best_group, best_channel)
    
    # 3. 按照频道聚合统计分组次数，合并频道并归入次数最多的分组
    channel_to_group_stats = {}
    channel_to_urls = {}
    excluded_channels = []  # 存储应排除聚合的频道 [{'channel': channel_name, 'group': group_name, 'urls': [url1, url2, ...]}]
    
    for url, (group, channel) in url_to_best_match.items():
        # 检查是否应排除在聚合之外
        should_exclude = should_exclude_from_aggregation(channel)
        
        if should_exclude:
            # 对于应排除聚合的频道，单独保存
            # 查找是否已存在相同频道和分组的记录
            existing_record = None
            for record in excluded_channels:
                if record['channel'] == channel and record['group'] == group:
                    existing_record = record
                    break
            
            if existing_record:
                # 如果已存在，添加 URL
                if url not in existing_record['urls']:
                    existing_record['urls'].append(url)
            else:
                # 如果不存在，创建新记录
                excluded_channels.append({'channel': channel, 'group': group, 'urls': [url]})
        else:
            # 统计频道的分组次数
            if channel not in channel_to_group_stats:
                channel_to_group_stats[channel] = {}
            channel_to_group_stats[channel][group] = channel_to_group_stats[channel].get(group, 0) + 1
            
            # 收集频道的所有 URL
            if channel not in channel_to_urls:
                channel_to_urls[channel] = []
            if url not in channel_to_urls[channel]:
                channel_to_urls[channel].append(url)
    
    # 为每个频道选择出现次数最多的分组
    channel_to_best_group = {}
    for channel, group_stats in channel_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_to_best_group[channel] = best_group
    
    # 不需要在这里合并应排除聚合的频道，因为它们会在步骤 4 中单独处理
    
    # 4. 构建分组-频道-URL 的结构
    group_channel_map = {}
    # 处理不应排除聚合的频道
    for channel, best_group in channel_to_best_group.items():
        urls = channel_to_urls[channel]
        if best_group not in group_channel_map:
            group_channel_map[best_group] = {}
        if channel not in group_channel_map[best_group]:
            group_channel_map[best_group][channel] = []
        # 合并所有 URL
        for url in urls:
            if url not in group_channel_map[best_group][channel]:
                group_channel_map[best_group][channel].append(url)
    
    # 处理应排除聚合的频道，保持原分组
    for record in excluded_channels:
        channel = record['channel']
        group = record['group']
        urls = record['urls']
        
        if group not in group_channel_map:
            group_channel_map[group] = {}
        if channel not in group_channel_map[group]:
            group_channel_map[group][channel] = []
        # 合并所有 URL
        for url in urls:
            if url not in group_channel_map[group][channel]:
                group_channel_map[group][channel].append(url)
    
    # 5. 如果分组下只有1个频道，且分组和频道名相同的，则将这些都合并到一个分组中，分组名"单剧"，频道名使用原频道名
    single_drama_group = "单剧"
    group_channel_map[single_drama_group] = {}
    
    # 收集需要移动的频道
    channels_to_move = []
    for group_name, channels in group_channel_map.items():
        if group_name == single_drama_group:
            continue
        if len(channels) == 1:
            channel_name = list(channels.keys())[0]
            if group_name == channel_name:
                channels_to_move.append((channel_name, channels[channel_name]))
    
    # 移动频道到"单剧"分组
    for channel_name, urls in channels_to_move:
        # 从原分组中移除
        for group_name, channels in list(group_channel_map.items()):
            if channel_name in channels:
                del channels[channel_name]
                # 如果分组为空，则删除分组
                if not channels:
                    del group_channel_map[group_name]
        # 添加到"单剧"分组
        if channel_name not in group_channel_map[single_drama_group]:
            group_channel_map[single_drama_group][channel_name] = []
        for url in urls:
            if url not in group_channel_map[single_drama_group][channel_name]:
                group_channel_map[single_drama_group][channel_name].append(url)
    
    # 6. 按照自定义规则排序分组，分组内按照频道名顺向排序
    # 转换为标准格式并排序
    merged_lives = []
    
    # 计算每个分组的统计信息，用于排序
    group_stats = []
    for group_name, channels in group_channel_map.items():
        if not channels:
            continue
        channel_count = len(channels)
        url_count = sum(len(urls) for urls in channels.values())
        # 计算比值
        ratio = url_count / channel_count if channel_count > 0 else 0
        group_stats.append((group_name, channels, channel_count, url_count, ratio))
    
    # 自定义排序规则
    def custom_sort_key(item):
        group_name, channels, channel_count, url_count, ratio = item
        if channel_count > 10:
            # 频道数>10：排在前面区域，按URL数/频道数的比值从大到小排序，相同时按频道数降序，再按分组名长度升序
            return (0, -ratio, -channel_count, len(group_name), group_name)
        else:
            # 频道数<=10：排在后面区域，按频道数从多到少排序，相同时按分组名长度升序
            return (1, -channel_count, len(group_name), group_name)
    
    # 按自定义规则排序
    sorted_groups = sorted(group_stats, key=custom_sort_key)
    
    for group_name, channels, channel_count, url_count, ratio in sorted_groups:
        # 跳过空分组
        if not channels:
            continue
        
        # 按照频道名顺向排序，支持字符串和末尾数字排序
        sorted_channels = sorted(channels.items(), key=lambda x: custom_channel_sort_key(x[0]))
        
        merged_channels = []
        for channel_name, urls in sorted_channels:
            merged_channels.append({
                'name': channel_name,
                'urls': urls
            })
        
        merged_lives.append({
            'group': group_name,
            'channels': merged_channels
        })
    
    return merged_lives

def validate_lives(lives, output_m3u_path=None, output_txt_path=None):
    """
    验证并清理 lives 数组
    :param lives: lives 数组
    :param output_m3u_path: m3u 输出文件路径
    :param output_txt_path: txt 输出文件路径
    :return: 验证后的 lives 数组
    """
    # 当调试模式为true时，输出原始lives
    if DEBUG_MODE:
        detail(f"[DEBUG] 输出原始 lives 到 {DEBUG_ORIGINAL_LIVES_FILE}")
        try:
            with open(DEBUG_ORIGINAL_LIVES_FILE, 'w', encoding='utf-8') as f:
                json.dump(lives, f, ensure_ascii=False, indent=2)
            detail(f"[DEBUG] 原始 lives 输出成功")
        except Exception as e:
            detail(f"[DEBUG] 输出原始 lives 失败: {e}")
    
    if not isinstance(lives, list):
        print("[Validate] lives 非数组，初始化为空数组")
        return []
    
    valid_lives = []
    # [4.1][并发] 第 1 步：纯结构校验（**不涉及网络**），标记出需要联网转换的元素
    marks = []            # True=结构合法直接收录；False=需尝试转换
    check_pg = Progress(len(lives), prefix="[mergeSources] 校验lives", interval=2.0)
    for element in lives:
        marks.append(validate_lives_element(element))
        check_pg.update(1)
    check_pg.finish()

    # [4.1][并发] 第 2 步：对不合法元素**并发**调用 convert_to_group_format()。
    # 该函数对 .m3u/.txt 源会发起网络请求；原先在主循环里串行逐个等待
    # （实测 152 个元素耗时 4分49秒），是 mergeSources 内部最大瓶颈，改为并发后按
    # "元素数 / 并发数" 收敛。ex.map 按输入顺序返回结果，故产物顺序与改造前完全一致。
    pending = [i for i, ok in enumerate(marks) if not ok]
    converted_map = {}    # {元素下标: 转换结果(list / dict / None)}
    if pending:
        detail(f"[Validate] {len(pending)} 个元素待尝试转换（并发 {CONVERT_WORKERS}）")
        conv_pg = Progress(len(pending), prefix="[mergeSources] 转换lives", interval=2.0)
        with ThreadPoolExecutor(max_workers=CONVERT_WORKERS) as ex:
            results = list(ex.map(lambda i: convert_to_group_format(lives[i]), pending))
        for i, res in zip(pending, results):
            converted_map[i] = res
            conv_pg.update(1)
        conv_pg.finish()

    # [4.1][并发] 第 3 步：按原始顺序汇总（顺序与改造前一致，保证产物稳定）
    for i, element in enumerate(lives):
        if marks[i]:
            valid_lives.append(element)
            continue
        detail("[Validate] 尝试转换非合法元素为group格式")
        converted = converted_map.get(i)
        if converted and isinstance(converted, list):
            detail(f"[Validate] 转换成功，添加 {len(converted)} 个group元素")
            valid_lives.extend(converted)
        elif converted:
            detail("[Validate] 转换成功，添加1个group元素")
            valid_lives.append(converted)
        else:
            detail("[Validate] 转换失败，跳过该元素")
    
    # 当调试模式为true时，输出转换后的valid_lives
    if DEBUG_MODE:
        detail(f"[DEBUG] 输出转换后的 valid_lives 到 {DEBUG_VALID_LIVES_FILE}")
        try:
            with open(DEBUG_VALID_LIVES_FILE, 'w', encoding='utf-8') as f:
                json.dump(valid_lives, f, ensure_ascii=False, indent=2)
            detail(f"[DEBUG] 转换后的 valid_lives 输出成功")
        except Exception as e:
            detail(f"[DEBUG] 输出转换后的 valid_lives 失败: {e}")
    
    # [4.1] 合并 iptv-org 公开直播源：并入后与原有 lives 走同一套 merge_lives_groups()，
    #       自动完成"同组同频道合并 + URL 去重"（去重逻辑与原有源完全一致）
    iptv_lives = fetch_iptv_org_lives()
    if iptv_lives:
        valid_lives.extend(iptv_lives)
        print(f"[IptvOrg] 已并入 {len(iptv_lives)} 个分组，合并前 lives 共 {len(valid_lives)} 个元素")
    
    # 合并结果
    merged_lives = merge_lives_groups(valid_lives)
    print(f"[Validate] lives 合并完成：从 {len(valid_lives)} 个元素合并为 {len(merged_lives)} 个元素")
    
    # [4.1] 内容过滤（空分组 / 分组黑名单 / 域名黑名单）
    # 被过滤的内容会按类型落盘为可复用的 tv.txt / tv.m3u（目录与运行日志相同）
    merged_lives, filtered_buckets = filter_lives(merged_lives)
    if filtered_buckets:
        n_filtered = sum(len(v) for v in filtered_buckets.values())
        detail_info = ", ".join(f"{k}={len(v)}" for k, v in filtered_buckets.items())
        print(f"[Filter] 已过滤 {n_filtered} 个分组（{detail_info}），"
              f"剩余 {len(merged_lives)} 个分组")
    
    # 转换为m3u格式并输出
    if output_m3u_path:
        m3u_content = lives_to_m3u(merged_lives)
        write_m3u_to_file(m3u_content, output_m3u_path)
    
    # 转换为txt格式并输出
    if output_txt_path:
        txt_content = lives_to_txt(merged_lives)
        write_txt_to_file(txt_content, output_txt_path)
    
    print(f"[Validate] lives 验证完成：共处理 {len(lives)} 个元素，生成 {len(merged_lives)} 个有效group元素")
    return merged_lives


def validate_sites(sites):
    """
    验证并清理 sites 数组
    :param sites: sites 数组
    :return: 验证后的 sites 数组
    """
    if not isinstance(sites, list):
        print("[Validate] sites 非数组，初始化为空数组")
        return []
    
    valid_sites = []
    for site in sites:
        if isinstance(site, dict) and all(field in site for field in SITES_REQUIRED_FIELDS):
            valid_sites.append(site)
    
    print(f"[Validate] sites 验证完成：{len(valid_sites)}/{len(sites)} 个元素有效")
    return valid_sites


def write_json_to_file(data, file_path=OUTPUT_FILE_PATH):
    try:
        with open(file_path, 'w', encoding='utf-8') as output_file:
            json.dump(data, output_file, indent=4, ensure_ascii=False)
        print(f"Data written to JSON file: {file_path}")
    except Exception as e:
        print(f"Error writing data to JSON file {file_path}: {str(e)}")

# ================= [新增] 深度递归替换相对路径函数 ==================
def deep_replace_relative_paths(obj, base_url):
    """
    深度递归遍历对象，替换字典中以 "./" 开头的字符串值
    :param obj: 当前遍历的对象 (dict/list/str)
    :param base_url: 用于拼接的基准 URL
    """
    # 需求 1：如果是本地文件源，不做处理
    if base_url.startswith((".", "/")):
        return

    if isinstance(obj, dict):
        for key, value in obj.items():
            if isinstance(value, str):
                # 需求 3：值为字符串且以 "./" 开头
                if value.startswith("./"):
                    # 需求 4：使用 urljoin 进行标准拼接
                    obj[key] = urljoin(base_url, value)
            else:
                # 递归处理下一层（不限制深度）
                deep_replace_relative_paths(value, base_url)
    elif isinstance(obj, list):
        # 如果是列表，遍历其中的元素继续递归
        for item in obj:
            deep_replace_relative_paths(item, base_url)
# =================================================================

def add_original_url(url, d):
    if 'originalUrl' not in d:
        d['originalUrl'] = []
    if isinstance(d['originalUrl'], str):
        d['originalUrl'] = [d['originalUrl'], url]
    else:
        d['originalUrl'].append(url)

def preprocess_single_dict(url, d):
    """
    针对单个单仓字典进行预处理
    """
    add_original_url(url, d)

    # 需求 2：不再单独处理 spider 字段

    # 需求 3：处理顶级 sites 下的字段
    if "sites" in d:
        # 确定基准 URL：优先使用 originalUrl 中的第一个，否则使用当前 url（对于本地文件也可以通过该方式进行正确替换）
        base_url_for_replace = url
        if "originalUrl" in d and isinstance(d["originalUrl"], list) and d["originalUrl"]:
            first_original_url = d["originalUrl"][0]
            if first_original_url and not first_original_url.startswith((".", "/")):
                base_url_for_replace = first_original_url

        # 执行深度替换
        deep_replace_relative_paths(d["sites"], base_url_for_replace)

# ================= [新增] 加载默认覆盖文件的函数 =================
def load_override_file(file_path):
    """
    加载并校验覆盖文件
    :param file_path: 覆盖文件路径
    :return: dict or None
    """
    p = Path(file_path)
    if not p.exists():
        print(f"[Override] 文件 {file_path} 不存在，跳过覆盖。")
        return None

    print(f"[Override] 发现覆盖文件: {file_path}，正在加载...")
    content = get_local_file_content(file_path)

    if content is None:
        print(f"[Override] 文件 {file_path} 读取失败或为空，跳过覆盖。")
        return None

    # 清洗注释
    content = remove_comments_from_string(content)
    content = content.replace("\n", "").replace("\r", "")

    if not is_json(content):
        print(f"[Override] 文件 {file_path} 不是合法的 JSON，跳过覆盖。")
        return None

    try:
        parsed = json.loads(content)
        if isinstance(parsed, dict):
            # 注意：Override 文件是本地文件，传入 url="" 或空，
            # deep_replace_relative_paths 内部会识别本地路径从而跳过处理，
            # 但为了保险，这里可以不调用 preprocess_single_dict，
            # 或者仅调用 add_original_url。
            # 这里选择仅做最简单的处理，因为 Override 通常是最终结果，不需要再解析相对路 径。
            print(f"[Override] 文件 {file_path} 加载成功，将在最后合并以覆盖参数。")
            return parsed
        else:
            print(f"[Override] 文件 {file_path} JSON 根节点不是 Object (dict)，跳过覆盖。")
            return None
    except Exception as e:
        print(f"[Override] 解析文件 {file_path} 时出错: {e}")
        return None

# 主函数
if __name__ == "__main__":
    input_file_path = INPUT_FILE_PATH
    current_time = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    output_file_path = current_time + "-" + OUTPUT_FILE_PATH
    output_m3u_path = current_time + "-" + DEFAULT_OUTPUT_M3U_FILE
    output_txt_path = current_time + "-" + DEFAULT_OUTPUT_TXT_FILE

    if len(sys.argv) > 1:
        input_file_path = sys.argv[1]
    if len(sys.argv) > 2:
        output_file_path = sys.argv[2]
    if len(sys.argv) > 3:
        output_m3u_path = sys.argv[3]
    if len(sys.argv) > 4:
        output_txt_path = sys.argv[4]

    # [日志] 逐条明细（抓取成败/校验跳过/转换成败等）写入文件，控制台仅保留进度与汇总
    print(f"[mergeSources] 详细日志写入: {logs_dir()}/merge_detail.log")

    # 1. 处理输入，获取原始数据
    raw_data_map, valid_sources, invalid_sources = process_input_file(input_file_path)

    # 利用 Pathlib 处理文件名
    p = Path(input_file_path)
    p2 = Path(output_file_path)
    filename = p.name
    single_file_path = p2.parent / f"{filename}.single"
    multi_file_path = p2.parent / f"{filename}.multi"
    
    # 定义新生成的文件名
    tmp_valid_path = p.parent / f"tmp.{filename}.valid-json"
    invalid_history_path = p2.parent / f"{filename}.invalid-json-history"
    
    # 提前读取无效历史文件，用于后续过滤
    invalid_history_set = set()
    if invalid_history_path.exists():
        try:
            with open(invalid_history_path, 'r', encoding='utf-8') as f:
                for l in f:
                    stripped = l.strip()
                    if stripped:
                        invalid_history_set.add(stripped)
            print(f"[Info] Loaded {len(invalid_history_set)} invalid history entries")
        except Exception as e:
            print(f"Warning: Could not read invalid history file {invalid_history_path}: {e}")

    # 收集所有输入的 URL（用于过滤）
    all_input_urls = set(raw_data_map.keys())

    # 2. 分类单仓与多仓，并收集最终待合并列表
    final_dicts_to_merge = []
    single_urls = []
    multi_urls = []

    print("\n" + "="*30)
    print("Starting Classification & Deep Scan")
    print("="*30)

    for url, data in raw_data_map.items():
        if is_single_cang(data):
            detail(f"[Single] {url}")
            single_urls.append(url)
            # 预处理并加入合并队列
            preprocess_single_dict(url, data)
            final_dicts_to_merge.append(data)
        else:
            print(f"[Multi]  {url}")
            detail(f"[Multi]  {url} -> Starting deep scan...")
            multi_urls.append(url)
            # 加入有效源列表，确保多仓URL会被写入到输入文件
            valid_sources.append(url)
            # 深度遍历提取 URL
            extracted_sub_urls = extract_urls_deep(data)
            # 去重
            extracted_sub_urls = list(dict.fromkeys(extracted_sub_urls))

            print(f"  Found {len(extracted_sub_urls)} potential URLs.")

            # 过滤 URL
            filtered_sub_urls = []
            for sub_url in extracted_sub_urls:
                # 检查是否在输入文件的 URL 中存在
                if sub_url in all_input_urls:
                    detail(f"  [Filter] Skipping URL (exists in input): {sub_url}")
                    continue
                # 检查是否在无效历史文件中存在
                if sub_url in invalid_history_set:
                    detail(f"  [Filter] Skipping URL (exists in invalid history): {sub_url}")
                    continue
                # 通过过滤，添加到处理列表
                filtered_sub_urls.append(sub_url)
            
            print(f"  After filtering: {len(filtered_sub_urls)} URLs to process")

            # [4.0] 并行解析过滤后的子 URL（网络 I/O 并发；解析逻辑保持不变）
            if filtered_sub_urls:
                scan_pg = Progress(len(filtered_sub_urls), prefix="[mergeSources] 解析子仓", interval=2.0)
                with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as ex:
                    parsed_results = list(ex.map(fetch_and_parse_single_cang, filtered_sub_urls))
                ok_n = skip_n = 0
                for sub_url, sub_data in zip(filtered_sub_urls, parsed_results):
                    if sub_data:
                        preprocess_single_dict(sub_url, sub_data)
                        final_dicts_to_merge.append(sub_data)
                        # 加入单仓URL列表，视同输入文件中的单仓处理
                        single_urls.append(sub_url)
                        # 加入有效源列表，确保会被写入到临时有效文件
                        valid_sources.append(sub_url)
                        ok_n += 1
                    else:
                        # 加入无效源列表，视同输入文件中的无效处理
                        invalid_sources.append(sub_url)
                        skip_n += 1
                    scan_pg.update(1)
                scan_pg.finish()
                print(f"[mergeSources] 子仓解析完成: 成功 {ok_n} / 跳过 {skip_n}")
            else:
                print(f"  No URLs to process after filtering")

    # ================= [修改] 加载覆盖文件，添加到待合并列表最后 =================
    override_data = load_override_file(DEFAULT_OVERRIDE_FILE)
    if override_data:
        print(f"[Override] Adding override data to merge list")
        final_dicts_to_merge.append(override_data)
    # ==========================================================

    # 3. 写入分类文件
    write_list_to_file(single_file_path, single_urls)
    write_list_to_file(multi_file_path, multi_urls)

    # 4. 合并所有字典（包含override）
    print("\n" + "="*30)
    print(f"Merging {len(final_dicts_to_merge)} single仓 data...")
    print("="*30)
    final_merged_dict = merge_dicts(final_dicts_to_merge)

    # 6. 验证并清理 lives 数组
    if 'lives' in final_merged_dict:
        print("\n" + "="*30)
        print("Validating lives array")
        print("="*30)
        final_merged_dict['lives'] = validate_lives(final_merged_dict['lives'], output_m3u_path, output_txt_path)
        
        # 检查 override 文件是否存在顶层 lives 字段
        if override_data and 'lives' in override_data:
            print("[Override] Using lives from override file instead of merged result")
            final_merged_dict['lives'] = override_data['lives']

    # 7. 验证并清理 sites 数组
    if 'video' in final_merged_dict and 'sites' in final_merged_dict['video']:
        print("\n" + "="*30)
        print("Validating sites array in video")
        print("="*30)
        final_merged_dict['video']['sites'] = validate_sites(final_merged_dict['video']['sites'])
    elif 'sites' in final_merged_dict:
        print("\n" + "="*30)
        print("Validating sites array")
        print("="*30)
        final_merged_dict['sites'] = validate_sites(final_merged_dict['sites'])

    # 8. 删除多余顶层字段
    print("\n" + "="*30)
    print("Removing extra top-level fields")
    print("="*30)
    removed_fields = []
    for field in EXTRA_FIELDS:
        if field in final_merged_dict:
            del final_merged_dict[field]
            removed_fields.append(field)
    if removed_fields:
        print(f"Removed fields: {', '.join(removed_fields)}")
    else:
        print("No extra fields found")

    # 9. 写入 JSON 结果文件
    write_json_to_file(final_merged_dict, output_file_path)

    # ================= 原有文件更新逻辑 =================

    # 记录有效的 JSON 源到 tmp 文件
    write_list_to_file(tmp_valid_path, valid_sources)

    # 记录无效的 JSON 源到历史文件（去重追加）
    for src in invalid_sources:
        append_to_file_unique(invalid_history_path, src, invalid_history_set)
        # 更新无效历史集合
        invalid_history_set.add(src)

    # 使用 tmp.valid-json 覆盖原输入文件
    replace_file(tmp_valid_path, input_file_path)
    print(f"[mergeSources] 无效源 {len(invalid_sources)} 个已记录到 {invalid_history_path.name}")
