#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试合并lives字段的算法
"""

import json
import os

def load_tv_json():
    """
    读取tv.json文件
    :return: tv.json的内容
    """
    file_path = os.path.join(os.path.dirname(__file__), 'tv.json')
    if not os.path.exists(file_path):
        print(f"[Error] tv.json文件不存在: {file_path}")
        return None
    
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return data
    except Exception as e:
        print(f"[Error] 读取tv.json文件失败: {e}")
        return None

def process_lives(lives):
    """
    处理lives字段，生成以URL为key的map，包含所有出现过的分组、频道及其次数
    :param lives: lives数组
    :return: URL到分组统计、频道统计的映射，以及URL总出现次数
    """
    # 初始化映射
    url_to_group_stats = {}  # URL -> {分组名: 出现次数}
    url_to_channel_stats = {}  # URL -> {频道名: 出现次数}
    url_to_count = {}  # URL -> 总出现次数
    
    if not isinstance(lives, list):
        print("[Error] lives不是数组")
        return url_to_group_stats, url_to_channel_stats, url_to_count
    
    total_urls_processed = 0
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
                
                total_urls_processed += 1
                # 更新分组统计
                if url not in url_to_group_stats:
                    url_to_group_stats[url] = {}
                url_to_group_stats[url][group_name] = url_to_group_stats[url].get(group_name, 0) + 1
                
                # 更新频道统计
                if url not in url_to_channel_stats:
                    url_to_channel_stats[url] = {}
                url_to_channel_stats[url][channel_name] = url_to_channel_stats[url].get(channel_name, 0) + 1
                
                # 更新总出现次数
                url_to_count[url] = url_to_count.get(url, 0) + 1
    
    # 打印聚合统计
    print(f"\n[Stats 1] URL聚合统计:")
    print(f"- 处理的URL总数量: {total_urls_processed}")
    print(f"- 去重后的URL数量: {len(url_to_group_stats)}")
    print(f"- 平均每个URL出现次数: {total_urls_processed / len(url_to_group_stats):.2f}次" if url_to_group_stats else "- 无URL数据")
    
    return url_to_group_stats, url_to_channel_stats, url_to_count

def get_most_frequent(stats_dict):
    """
    获取出现次数最多的键
    :param stats_dict: 统计字典 {键: 次数}
    :return: 出现次数最多的键
    """
    if not stats_dict:
        return '未分组'
    return max(stats_dict.items(), key=lambda x: x[1])[0]

def select_best_matches(url_to_group_stats, url_to_channel_stats):
    """
    为每个URL选择出现次数最多的分组和频道
    :param url_to_group_stats: URL到分组统计的映射
    :param url_to_channel_stats: URL到频道统计的映射
    :return: URL到(分组, 频道)的映射
    """
    url_to_best_match = {}
    
    for url, group_stats in url_to_group_stats.items():
        # 选择出现次数最多的分组
        best_group = get_most_frequent(group_stats)
        
        # 选择出现次数最多的频道
        channel_stats = url_to_channel_stats.get(url, {})
        best_channel = get_most_frequent(channel_stats)
        
        url_to_best_match[url] = (best_group, best_channel)
    
    # 打印选择结果统计
    print(f"\n[Stats 2] 选择最多出现次数的结果统计:")
    print(f"- 处理的URL数量: {len(url_to_best_match)}")
    
    # 统计唯一的分组和频道组合
    unique_combinations = set(url_to_best_match.values())
    print(f"- 唯一的分组-频道组合数量: {len(unique_combinations)}")
    
    # 统计每个分组包含的频道数
    group_channel_count = {}
    for group, channel in unique_combinations:
        if group not in group_channel_count:
            group_channel_count[group] = set()
        group_channel_count[group].add(channel)
    
    print(f"- 涉及的分组数量: {len(group_channel_count)}")
    print(f"- 平均每个分组包含的频道数: {sum(len(channels) for channels in group_channel_count.values()) / len(group_channel_count):.2f}个" if group_channel_count else "- 无分组数据")
    
    return url_to_best_match

def build_group_channel_map(url_to_best_match):
    """
    根据URL到最佳匹配的映射，构建分组-频道-URL的结构
    :param url_to_best_match: URL到(分组, 频道)的映射
    :return: 分组-频道-URL的结构
    """
    group_channel_map = {}
    
    for url, (group, channel) in url_to_best_match.items():
        # 确保分组存在
        if group not in group_channel_map:
            group_channel_map[group] = {}
        
        # 确保频道存在
        if channel not in group_channel_map[group]:
            group_channel_map[group][channel] = []
        
        # 添加URL
        if url not in group_channel_map[group][channel]:
            group_channel_map[group][channel].append(url)
    
    return group_channel_map

def merge_same_names(group_channel_map):
    """
    实现分组-频道-URL自上而下的同名合并
    :param group_channel_map: 分组-频道-URL的结构
    :return: 合并后的分组-频道-URL结构
    """
    # 初始化合并结果
    merged_result = {}
    
    # 按分组合并
    for group_name, channels in group_channel_map.items():
        # 确保分组存在
        if group_name not in merged_result:
            merged_result[group_name] = {}
        
        # 按频道合并
        for channel_name, urls in channels.items():
            # 确保频道存在
            if channel_name not in merged_result[group_name]:
                merged_result[group_name][channel_name] = []
            
            # 添加并去重URL
            for url in urls:
                if url not in merged_result[group_name][channel_name]:
                    merged_result[group_name][channel_name].append(url)
    
    # 打印合并结果统计
    print(f"\n[Stats 3] 同名合并结果统计:")
    print(f"- 合并后的分组数量: {len(merged_result)}")
    
    total_channels = sum(len(channels) for channels in merged_result.values())
    print(f"- 合并后的频道总数: {total_channels}")
    
    total_urls = sum(len(urls) for channels in merged_result.values() for urls in channels.values())
    print(f"- 合并后的URL总数: {total_urls}")
    
    print(f"- 平均每个分组包含的频道数: {total_channels / len(merged_result):.2f}个" if merged_result else "- 无分组数据")
    print(f"- 平均每个频道包含的URL数: {total_urls / total_channels:.2f}个" if total_channels > 0 else "- 无频道数据")
    
    return merged_result

def convert_to_group_format(group_channel_map):
    """
    将分组-频道-URL结构转换为group格式
    :param group_channel_map: 分组-频道-URL的结构
    :return: group格式的数组
    """
    result = []
    
    for group_name, channels in group_channel_map.items():
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

def save_lives_single(lives_data):
    """
    保存lives数据到lives.single.json文件
    :param lives_data: lives数据
    """
    file_path = os.path.join(os.path.dirname(__file__), 'lives.single.json')
    
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(lives_data, f, ensure_ascii=False, indent=2)
        print(f"\n[Success] 保存lives.single.json成功: {file_path}")
        print(f"[Info] 生成的group元素数量: {len(lives_data)}")
    except Exception as e:
        print(f"[Error] 保存lives.single.json失败: {e}")

def main():
    """
    主函数
    """
    print("[Info] 开始测试合并lives字段的算法...")
    
    # 读取tv.json
    data = load_tv_json()
    if not data:
        return
    
    # 获取lives字段
    lives = data.get('lives', [])
    print(f"[Info] 读取到 {len(lives)} 个lives元素")
    
    # 1. 按URL聚合并统计
    url_to_group_stats, url_to_channel_stats, url_to_count = process_lives(lives)
    
    # 2. 为每个URL选择最多出现次数的分组和频道
    url_to_best_match = select_best_matches(url_to_group_stats, url_to_channel_stats)
    
    # 3. 构建分组-频道-URL结构
    group_channel_map = build_group_channel_map(url_to_best_match)
    
    # 4. 进行同名合并
    merged_map = merge_same_names(group_channel_map)
    
    # 5. 转换为group格式
    merged_lives = convert_to_group_format(merged_map)
    
    # 6. 保存到文件
    save_lives_single(merged_lives)
    
    print("\n[Info] 测试完成！")

if __name__ == "__main__":
    main()
