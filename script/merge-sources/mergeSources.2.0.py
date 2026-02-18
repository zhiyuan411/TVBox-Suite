#! /usr/bin/env python3
# pip install deepmerge
from deepmerge import Merger
import datetime
import json
import sys
import re
import requests
from pathlib import Path

# 定义常量
INPUT_FILE_PATH = "input.txt"
OUTPUT_FILE_PATH = "output.txt"

def remove_comments_from_string(input_string):
    # 使用正则表达式替换所有以 // 或 # 等行注释符开头直到行末的内容
    # r'//[^\n]*' 匹配以 // 开头直到行末的内容
    # re.MULTILINE 使得 ^ 和 $ 分别匹配每一行的开始和结束
    input_string = re.sub(r'^[ ]*//[^\n]*', '', input_string, flags=re.MULTILINE)
    input_string = re.sub(r'^[ ]*#[^\n]*', '', input_string, flags=re.MULTILINE)
    input_string = re.sub(r'^[ ]*/\*.*?\*/', '', input_string, flags=re.DOTALL)
    return input_string

def is_json(content):
    """检查内容是否为有效的 JSON 字符串"""
    try:
        json.loads(content)
    except ValueError:
        return False
    return True

def get_local_file_content(file_path):
    """读取本地文件内容"""
    try:
        with open(file_path, 'r') as file:
            content = file.read()
        print(f"Read local file: {file_path}")
        return content
    except Exception as e:
        print(f"Error reading local file {file_path}: {e}")
        return None

def get_url_content(url, timeout=10):
    """通过 URL 获取内容，并设置超时时间"""
    try:
        response = requests.get(url, timeout=timeout)
        response.raise_for_status()  # 检查 HTTP 请求是否成功

        # 获取 Content-Type 头中的 charset 参数
        content_type = response.headers.get('Content-Type', '')
        
        # 如果 Content-Type 头中不携带 charset 参数，默认使用 UTF-8 编码
        if 'charset=' not in content_type:
            content = response.content.decode('utf-8')
        else:
            # 如果 Content-Type 头中携带 charset 参数，使用 requests 自动处理
            content = response.text

        print(f"Fetched URL: {url}")
        return content
    except requests.Timeout as e:
        print(f"Request timed out for URL {url}: {e}")
        return None
    except requests.RequestException as e:
        print(f"Error fetching URL {url}: {e}")
        return None

def detect_encoding(s):
    # 尝试 UTF-8 编码
    try:
        content_bytes = s.encode('utf-8')
        return 'utf-8'
    except UnicodeEncodeError:
        pass
    
    # 尝试 GBK 编码
    try:
        content_bytes = s.encode('gbk')
        return 'gbk'
    except UnicodeEncodeError:
        pass
    
    # 如果以上都不行，返回 None
    return None

def process_input_file(input_file_path=INPUT_FILE_PATH):
    """处理输入文件"""
    results = {}
    try:
        with open(input_file_path, 'r') as input_file:
            for line in input_file:
                trimmed_line = line.strip()
                print(f"Processing line: {trimmed_line}")

                if trimmed_line.startswith('/') or trimmed_line.startswith('.'):
                    content = get_local_file_content(trimmed_line)
                elif trimmed_line.startswith('http'):
                    content = get_url_content(trimmed_line)
                else:
                    print("Line does not start with '/' or 'http', skipping.")
                    continue

                if content is not None:
                    # 去除注释
                    content = remove_comments_from_string(content)

                    # 去除换行符，以防有些字段值内部使用导致解析非法
                    content = content.replace("\n", "").replace("\r", "")
                    #print(content)

                if content is not None and is_json(content):
                    # 检测 content 的编码
                    encoding = detect_encoding(content)
                    # 根据检测到的编码解码 content （得到 Unicode 字符串）
                    if encoding == 'gbk':
                        content = content.encode('gbk').decode('gbk')
                    else:
                        content = content.encode('utf-8').decode('utf-8')
                    parsed_dict = json.loads(content)
                    results[trimmed_line] = parsed_dict
                    print("Parsed JSON to dict successfully.")
                else:
                    print("Content is not valid JSON, skipping.")

        return results
    except FileNotFoundError:
        print(f"The file {input_file_path} was not found.")
    except Exception as e:
        print(f"An error occurred while processing the file: {e}")


def custom_list_merge(merger, path, list1, list2):
    # 检查列表中的元素是否为字典类型
    if all(isinstance(item, dict) for item in list1 + list2):
        # 创建一个字典来存储 key 或 id 对应的字典
        key_id_dict = {}
        # 字段数组，用于查找唯一的标识符
        identifier_fields = ['key', 'id', 'name']

        for item in list1:
            # 查找第一个存在的标识符字段
            identifier = next((item.get(field) for field in identifier_fields if item.get(field)), None)
            if identifier is not None:
                key_id_dict[identifier] = item
            else:
                # 如果没有找到标识符字段，则直接添加
                key_id_dict[id(item)] = item

        # 合并 list2 中的字典
        for item in list2:
            # 查找第一个存在的标识符字段
            identifier = next((item.get(field) for field in identifier_fields if item.get(field)), None)
            if identifier is not None:
                if identifier in key_id_dict:
                    # 如果标识符已经存在，则更新字典
                    key_id_dict[identifier].update(item)
                else:
                    # 如果标识符不存在，则添加字典
                    key_id_dict[identifier] = item
            else:
                # 如果没有找到标识符字段，则直接添加
                key_id_dict[id(item)] = item

        # 将字典转换回列表
        merged_list = list(key_id_dict.values())
        return merged_list
    else:
        # 如果列表中的元素不是字典类型，则union方式合并不重复项
        unique_items = set(list1).union(set(list2))
        return list(unique_items)


# 创建一个自定义的合并策略
custom_merger = Merger(
    [
     # 列表合并策略
     (list, custom_list_merge),
     # 集合合并策略
     (set, "union"),
     # 元组合并策略
     (tuple, "concat"),
     # 字典合并策略
     (dict, "merge"),
    ],
    # 当遇到不可合并的类型时，使用覆盖策略
    ["override"],
    # 当遇到不可合并的类型时，使用覆盖策略
    ["override"]
)

# 示例字典
dict1 = {
    "name": "Alice",
    "age": 30,
    "details": {
        "height": 165,
        "weight": 60,
        "hobbies": [{"hours": 2}, {"hours": 1}],
        "hobbiesWithKey": [{"key": "reading", "hours": 2}, {"key": "painting", "hours": 1}],
        "numbers": [1, 2, 3]
    }
}

dict2 = {
    "age": 35,
    "details": {
        "height": 170,
        "skills": ["coding", "cooking"],
        "hobbies": [{"hours": 3}, {"hours": 1}],
        "hobbiesWithKey": [{"key": "reading", "hours": 3}, {"key": "swimming", "hours": 1}],
        "numbers": [4, 5, 6]
    }
}

# 使用自定义合并策略
#merged_dict = custom_merger.merge(dict1, dict2)
#print(merged_dict)

def merge_dicts(dicts_list):
    """合并列表中的所有字典"""
    merged_dict = {}
    for d in dicts_list:
        merged_dict = custom_merger.merge(merged_dict, d)
    return merged_dict

def write_json_to_file(data, file_path=OUTPUT_FILE_PATH):
    """将数据写入 JSON 文件"""
    try:
        with open(file_path, 'w', encoding='utf-8') as output_file:
            json.dump(data, output_file, indent=4, ensure_ascii=False)
        print(f"Data written to JSON file: {file_path}")
    except Exception as e:
        print(f"Error writing data to JSON file {file_path}: {str(e)}")


def flatten_video_content(d):
    """
    如果字典 d 包含 "video" 键，则将其内容移至顶层字典并删除 "video" 键。

    :param d: 输入字典
    """
    if "video" in d:
        # 将 "video" 下的内容移动到顶层字典
        for key, value in d["video"].items():
            d[key] = value

        # 删除 "video" 键
        del d["video"]

def rename_keys(d, pairs):
    """
    根据键值对字典 pairs 重命名字典 d 中的键。

    :param d: 主字典
    :param pairs: 键值对字典，用于重命名 d 中的键
    """
    for old_key, new_key in pairs.items():
        if old_key in d:
            # 保存旧键的值
            value = d.pop(old_key)
            # 将值赋给新键
            d[new_key] = value

def process_spider_value(url, spider_value):
    # 如果url为本地文件则不做额外处理
    if url.startswith((".", "/")):
        return spider_value

    # 检查 spider_value 是否需要拼接处理
    if not spider_value.startswith((".", "/")):
        return spider_value

    # 分离 URL 的基地址和路径
    base_url = url.split("/", 1)[0]  # 获取协议和域名部分

    # 根据 spider_value 的不同前缀进行处理
    if spider_value.startswith("."):
        # 保留除最后一个 '/' 以外的所有路径
        path = url[len(base_url):].rstrip("/").rsplit("/", 1)[0]
        # 将 '.' 替换为 '/'
        processed_spider_value = spider_value.replace(".", "/", 1)
    else:  # spider_value.startswith("/")
        # 保留 URL 的主机部分
        path = ""
        processed_spider_value = spider_value.lstrip("/")

    # 拼接新的 URL
    new_url = base_url + path + "/" + processed_spider_value.lstrip("/")

    return new_url

def process_spider(url, d):
    """
    处理输入字典 d，如果 d 有 "spider" 字段，则：
    如果 d 有 "sites" 字段且为数组，则将它的数组中所有 dict 类型的元素添加一个 "jar" 字段且其值设置为 d["spider"]。
    如果已经存在 "jar" 字段且不为空字符串，则跳过处理。

    :param d: 输入的字典
    """
    # 检查是否有 "spider" 字段
    if "spider" in d:
        spider_value = d["spider"]

        # 对spider地址进行预处理
        spider_value = process_spider_value(url, spider_value)

        # 检查是否有 "sites" 字段且为列表
        if "sites" in d and isinstance(d["sites"], list):
            sites = d["sites"]

            # 遍历 "sites" 字段中的每个元素
            for site in sites:
                # 检查元素是否为字典类型
                if isinstance(site, dict):
                    # 检查是否已有 "jar" 字段且不为空字符串
                    if "jar" not in site or site["jar"] == "":
                        site["jar"] = spider_value
                    else:
                        site["jar"] = process_spider_value(url, site["jar"])

def add_original_url(url, d):
    # 检查URL是否以点或斜线开头，如果不是则继续处理
    #if not url.startswith('.') and not url.startswith('/'):
        # 如果'originalUrl'不存在于字典d中，则创建一个新的空列表
        if 'originalUrl' not in d:
            d['originalUrl'] = []

        # 检查'originalUrl'是否已经是字符串类型
        if isinstance(d['originalUrl'], str):
            # 将原来的字符串转换为列表
            d['originalUrl'] = [d['originalUrl'], url]
        else:
            # 如果'originalUrl'已经是列表，则直接追加新的URL
            d['originalUrl'].append(url)

def preprocess_result(result):
    """
    对结果进行预处理函数。

    :param result: dict内容为url和对应的字典数组
    """

    rename_keys_dict = {
        "iptv": "lives",
        "channel": "lives",
        "analyze": "parses"
    }


    for key, value in result.items():
        flatten_video_content(value)
        add_original_url(key, value)
        if "originalUrl" in value and isinstance(value["originalUrl"], list) and \
        value["originalUrl"] and value["originalUrl"][0] != "":
            process_spider(value["originalUrl"][0], value)
        else:
            process_spider(key, value)
        rename_keys(value, rename_keys_dict)



# 主函数
if __name__ == "__main__":
    # 从命令行参数获取输入和输出文件路径
    input_file_path = INPUT_FILE_PATH
    # 使用当前时间生成默认输出文件名
    current_time = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    output_file_path = current_time + "-" + OUTPUT_FILE_PATH

    # 如果提供了命令行参数，则使用它们
    if len(sys.argv) > 1:
        input_file_path = sys.argv[1]
    if len(sys.argv) > 2:
        output_file_path = sys.argv[2]

    result = process_input_file(input_file_path)
    result_dicts = list(result.values())
    #print("All parsed dictionaries:", result_dicts)

    # 预处理所有数据
    preprocess_result(result)

    # 合并所有字典
    final_merged_dict = merge_dicts(result_dicts)
    #print("Merged dictionary:", final_merged_dict)

    # 写入 JSON 文件
    write_json_to_file(final_merged_dict, output_file_path)
