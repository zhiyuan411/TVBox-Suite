#! /usr/bin/env python3
 
import json
 
def extract_structure(data, n=1):
    """
    获取字典的结构，对于字典类型的值保留所有的键值对，
    对于数组类型的值保留数组类型，但只保留前n个元素。
    :param data: 输入的数据（可以是字典或列表）
    :param n: 保留的数组元素数量，默认为1
    :return: 处理后的数据结构
    """
    if isinstance(data, dict):
        # 如果是字典类型，则递归处理每个键值对
        return {key: extract_structure(value, n) for key, value in data.items()}
    elif isinstance(data, list):
        # 如果是列表类型，则保留前n个元素，并保持列表类型
        trimmed_list = data[:n]
        return [extract_structure(item, n) for item in trimmed_list]
    else:
        # 如果既不是字典也不是列表，则直接返回该值
        return data
 
def read_and_process_json_file(file_path, n=1):
    """
    从指定文件中读取 JSON 字符串内容，并使用 extract_structure 函数解析内容。
    :param file_path: 文件路径
    :param n: 保留的数组元素数量，默认为1
    :return: 解析后的数据结构
    """
    with open(file_path, 'r') as file:
        json_data = json.load(file)
 
    processed_data = extract_structure(json_data, n)
 
    return processed_data
 
# 示例使用
file_path = 'tv.json'  # 请确保替换为实际文件路径
 
# 调用函数
result = read_and_process_json_file(file_path, n=2)
 
# 将处理后的字典转换为美化格式的 JSON 字符串并输出
formatted_json = json.dumps(result, indent=4, ensure_ascii=False)
print(formatted_json)
