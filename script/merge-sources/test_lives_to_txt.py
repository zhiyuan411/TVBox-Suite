#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试脚本：将合并后的 lives 字段转换为 TXT 格式
输入：script/merge-sources/output_lives.json
输出：script/merge-sources/output_lives.txt
"""

import json
import os
from urllib.parse import quote


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


def calculate_stats(lives):
    """
    计算统计数据
    :param lives: lives 数组
    :return: 统计数据字典
    """
    stats = {
        'total_groups': 0,
        'total_channels': 0,
        'total_urls': 0,
        'group_stats': []
    }
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        
        group_name = group_item.get('group', '未分组')
        channels = group_item.get('channels', [])
        
        group_channel_count = len(channels)
        group_url_count = sum(len(channel.get('urls', [])) for channel in channels)
        
        stats['total_groups'] += 1
        stats['total_channels'] += group_channel_count
        stats['total_urls'] += group_url_count
        
        stats['group_stats'].append({
            'group_name': group_name,
            'channel_count': group_channel_count,
            'url_count': group_url_count
        })
    
    return stats


def print_stats(stats):
    """
    打印统计数据
    :param stats: 统计数据字典
    """
    print("\n统计数据:")
    print(f"总分组数: {stats['total_groups']}")
    print(f"总频道数: {stats['total_channels']}")
    print(f"总URL数: {stats['total_urls']}")
    
    print("\n分组详细统计:")
    for i, group_stat in enumerate(stats['group_stats']):
        print(f"{i+1}. {group_stat['group_name']}: 频道数={group_stat['channel_count']}, URL数={group_stat['url_count']}")


def main():
    """
    主函数：读取输入文件，转换格式，输出结果
    """
    # 构建文件路径
    input_path = os.path.join(os.path.dirname(__file__), "output_lives.json")
    output_path = os.path.join(os.path.dirname(__file__), "output_lives.txt")
    
    print(f"输入文件路径: {input_path}")
    print(f"输出文件路径: {output_path}")
    
    # 检查输入文件是否存在
    if not os.path.exists(input_path):
        print(f"错误: 输入文件 {input_path} 不存在")
        return
    
    try:
        # 读取输入文件
        with open(input_path, 'r', encoding='utf-8') as f:
            lives = json.load(f)
        
        print(f"成功读取输入文件，包含 {len(lives)} 个分组")
        
        # 计算统计数据
        stats = calculate_stats(lives)
        
        # 打印统计数据
        print_stats(stats)
        
        # 转换为 TXT 格式
        txt_content = lives_to_txt(lives)
        
        # 输出到文件
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write(txt_content)
        
        print(f"\n转换完成！")
        print(f"TXT 格式输出已保存到: {output_path}")
        print(f"输出文件行数: {len(txt_content.split(chr(10)))}")
        
    except Exception as e:
        print(f"处理过程中出错: {str(e)}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
