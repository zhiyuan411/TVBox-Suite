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
        content = response.text
        print(f"Fetched URL: {url}")
        return content
    except requests.Timeout as e:
        print(f"Request timed out for URL {url}: {e}")
        return None
    except requests.RequestException as e:
        print(f"Error fetching URL {url}: {e}")
        return None
 
def process_input_file(input_file_path=INPUT_FILE_PATH):
    """处理输入文件"""
    results = []
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
                    parsed_dict = json.loads(content)
                    results.append(parsed_dict)
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
        # 如果列表中的元素不是字典类型，则简单地合并列表
        return list1 + list2
 
 
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
        with open(file_path, 'w') as output_file:
            json.dump(data, output_file, indent=4)
        print(f"Data written to JSON file: {file_path}")
    except Exception as e:
        print(f"Error writing data to JSON file {file_path}: {str(e)}")
 
 
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
 
    result_dicts = process_input_file(input_file_path)
    #print("All parsed dictionaries:", result_dicts)
 
    # 合并所有字典
    final_merged_dict = merge_dicts(result_dicts)
    #print("Merged dictionary:", final_merged_dict)
 
    # 写入 JSON 文件
    write_json_to_file(final_merged_dict, output_file_path)
