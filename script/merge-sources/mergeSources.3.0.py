#! /usr/bin/env python3
# pip install deepmerge charset-normalizer requests
from deepmerge import Merger
import datetime
import json
import sys
import re
import requests
from pathlib import Path
from urllib.parse import urljoin  # [新增] 用于标准路径拼接

from charset_normalizer import from_bytes


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
                print("  [Skip] 检测到二进制文件头，跳过解码。")
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
        print(f"Read local file: {file_path}")

        # 解码
        content = decode_safely(byte_content)
        return content
    except Exception as e:
        print(f"Error reading local file {file_path}: {e}")
        return None

def get_url_content(url, timeout=10):
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
            print(f"  [Skip] URL Content-Type 为非文本类型: {content_type}")
            return None

        print(f"Fetched URL: {url}")

        # 解码
        content = decode_safely(byte_content)
        return content
    except requests.Timeout as e:
        print(f"Request timed out for URL {url}: {e}")
        return None
    except requests.RequestException as e:
        print(f"Error fetching URL {url}: {e}")
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
            print(f"Appended to file: {line} -> {file_path.name}")
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
    print(f"  [Multi->Single] Fetching sub-url: {url}")
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
            for line in input_file:
                trimmed_line = line.strip()
                if not trimmed_line:
                    continue

                print(f"Processing line: {trimmed_line}")
                content = None

                if trimmed_line.startswith('/') or trimmed_line.startswith('.'):
                    content = get_local_file_content(trimmed_line)
                elif trimmed_line.startswith('http'):
                    content = get_url_content(trimmed_line)
                else:
                    print("Line does not start with '/' or 'http', skipping.")
                    invalid_sources.append(trimmed_line)
                    continue

                if content is not None:
                    content = remove_comments_from_string(content)
                    content = content.replace("\n", "").replace("\r", "")

                if content is not None and is_json(content):
                    try:
                        parsed_dict = json.loads(content)
                        raw_data_map[trimmed_line] = parsed_dict
                        valid_sources.append(trimmed_line)
                        print("Parsed JSON successfully.")
                    except Exception as e:
                        print(f"JSON 解析失败: {e}")
                        invalid_sources.append(trimmed_line)
                else:
                    print("Content is not valid JSON, skipping.")
                    invalid_sources.append(trimmed_line)

        return raw_data_map, valid_sources, invalid_sources
    except FileNotFoundError:
        print(f"The file {input_file_path} was not found.")
        return {}, [], []
    except Exception as e:
        print(f"An error occurred while processing the file: {e}")
        return {}, [], []


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
        print("  [Validate] 跳过：非字典元素")
        return False
    
    # 检查是否包含必要字段
    if 'group' not in element:
        print("  [Validate] 跳过：缺少 group 字段")
        return False
    
    if 'channels' not in element:
        print("  [Validate] 跳过：缺少 channels 字段")
        return False
    
    # 检查 group 字段是否为非空字符串
    if not isinstance(element['group'], str) or not element['group'].strip():
        print("  [Validate] 跳过：group 字段为空或非字符串")
        return False
    
    # 检查 channels 字段是否为数组
    if not isinstance(element['channels'], list):
        print("  [Validate] 跳过：channels 字段非数组")
        return False
    
    # 检查 channels 数组是否为空
    if not element['channels']:
        print("  [Validate] 跳过：channels 数组为空")
        return False
    
    # 检查是否包含 proxy://，如果包含则视为无效
    element_str = json.dumps(element)
    if 'proxy://' in element_str:
        print("  [Validate] 跳过：包含 proxy://")
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
        print("  [Validate] 跳过：channels 数组中无合法频道")
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
        print(f"[Convert] m3u解析失败: {e}")
        return None

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
        print(f"[Convert] txt解析失败: {e}")
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
        content = get_url_content(url)
        if content:
            return parse_m3u_content(content)
        return None
    
    elif url_lower.endswith('.txt'):
        # 处理txt类型，根据内容判断实际格式
        content = get_url_content(url)
        if content:
            # 根据内容特征判断是m3u还是txt格式
            if content.strip().startswith('#EXTM3U'):
                print("[Convert] 检测到txt后缀的m3u格式内容")
                return parse_m3u_content(content)
            else:
                print("[Convert] 检测到txt格式内容")
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
            print(f"[Convert] m3u8转换失败: {e}")
            return None
    
    return None

def get_most_frequent(stats_dict):
    """
    获取出现次数最多的键
    :param stats_dict: 统计字典 {键: 次数}
    :return: 出现次数最多的键
    """
    if not stats_dict:
        return '未分组'
    return max(stats_dict.items(), key=lambda x: x[1])[0]

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
        
        group_name = group_item.get('group', '未分组')
        channels = group_item.get('channels', [])
        
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            channel_name = channel_item.get('name', '未命名')
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
        
        group_name = group_item.get('group', '未分组')
        channels = group_item.get('channels', [])
        
        # 添加分组定义
        txt_lines.append(f"{group_name},#genre#")
        
        # 添加频道定义
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            channel_name = channel_item.get('name', '未命名')
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
            # 频道数>10：排在前面区域，按URL数/频道数的比值从大到小排序，相同时按频道数降序
            return (0, -ratio, -channel_count, group_name)
        else:
            # 频道数<=10：排在后面区域，按频道数从多到少排序
            return (1, -channel_count, 0.0, group_name)
    
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
    if not isinstance(lives, list):
        print("[Validate] lives 非数组，初始化为空数组")
        return []
    
    valid_lives = []
    for element in lives:
        if validate_lives_element(element):
            valid_lives.append(element)
        else:
            # 尝试转换为group格式
            print("[Validate] 尝试转换非合法元素为group格式")
            converted = convert_to_group_format(element)
            if converted and isinstance(converted, list):
                print(f"[Validate] 转换成功，添加 {len(converted)} 个group元素")
                valid_lives.extend(converted)
            elif converted:
                print("[Validate] 转换成功，添加1个group元素")
                valid_lives.append(converted)
            else:
                print("[Validate] 转换失败，跳过该元素")
    
    # 合并结果
    merged_lives = merge_lives_groups(valid_lives)
    print(f"[Validate] lives 合并完成：从 {len(valid_lives)} 个元素合并为 {len(merged_lives)} 个元素")
    
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
            print(f"[Single] {url}")
            single_urls.append(url)
            # 预处理并加入合并队列
            preprocess_single_dict(url, data)
            final_dicts_to_merge.append(data)
        else:
            print(f"[Multi]  {url} -> Starting deep scan...")
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
                    print(f"  [Filter] Skipping URL (exists in input): {sub_url}")
                    continue
                # 检查是否在无效历史文件中存在
                if sub_url in invalid_history_set:
                    print(f"  [Filter] Skipping URL (exists in invalid history): {sub_url}")
                    continue
                # 通过过滤，添加到处理列表
                filtered_sub_urls.append(sub_url)
            
            print(f"  After filtering: {len(filtered_sub_urls)} URLs to process")

            # 尝试解析每一个过滤后的 URL
            for sub_url in filtered_sub_urls:
                sub_data = fetch_and_parse_single_cang(sub_url)
                if sub_data:
                    print(f"  [OK] Resolved as single仓: {sub_url}")
                    preprocess_single_dict(sub_url, sub_data)
                    final_dicts_to_merge.append(sub_data)
                    # 加入单仓URL列表，视同输入文件中的单仓处理
                    single_urls.append(sub_url)
                    # 加入有效源列表，确保会被写入到临时有效文件
                    valid_sources.append(sub_url)
                else:
                    print(f"  [SKIP] Not valid JSON or not dict: {sub_url}")
                    # 加入无效源列表，视同输入文件中的无效处理
                    invalid_sources.append(sub_url)

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
