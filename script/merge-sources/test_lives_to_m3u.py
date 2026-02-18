#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试将合并后的 lives 数据转换为 M3U 格式的脚本
输入：test_merge_lives.2.0.py 的输出文件 output_lives.json
输出：M3U 格式文件和统计数据
"""

import json
import os


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
    :return: 是否写入成功
    """
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(m3u_content)
        print(f"M3U 内容已写入到: {file_path}")
        return True
    except Exception as e:
        print(f"写入 M3U 文件失败: {str(e)}")
        return False


def analyze_lives_data(lives):
    """
    分析 lives 数据并打印统计信息
    :param lives: lives 数组
    :return: 统计信息字典
    """
    if not isinstance(lives, list):
        return {}
    
    stats = {
        'total_groups': len(lives),
        'total_channels': 0,
        'total_urls': 0,
        'group_stats': []
    }
    
    print("\n=== 详细统计信息 ===")
    
    for i, group in enumerate(lives):
        if not isinstance(group, dict):
            continue
        
        group_name = group.get('group', '未知')
        channels = group.get('channels', [])
        channel_count = len(channels)
        url_count = sum(len(channel.get('urls', [])) for channel in channels)
        
        stats['total_channels'] += channel_count
        stats['total_urls'] += url_count
        
        stats['group_stats'].append({
            'group_name': group_name,
            'channel_count': channel_count,
            'url_count': url_count
        })
        
        # 打印分组统计
        print(f"{i+1}. {group_name}: 频道数={channel_count}, URL数={url_count}")
    
    # 打印总体统计
    print("\n=== 总体统计 ===")
    print(f"总分组数: {stats['total_groups']}")
    print(f"总频道数: {stats['total_channels']}")
    print(f"总URL数: {stats['total_urls']}")
    print(f"平均每个分组的频道数: {stats['total_channels'] / stats['total_groups']:.2f}")
    print(f"平均每个频道的URL数: {stats['total_urls'] / stats['total_channels']:.2f}")
    
    return stats


def main():
    """
    主函数：读取输入文件，转换为 M3U 格式，输出结果
    """
    # 构建文件路径
    input_path = os.path.join(os.path.dirname(__file__), "output_lives.json")
    output_m3u_path = os.path.join(os.path.dirname(__file__), "output_lives.m3u")
    output_stats_path = os.path.join(os.path.dirname(__file__), "output_stats.json")
    
    print(f"输入文件路径: {input_path}")
    print(f"输出 M3U 文件路径: {output_m3u_path}")
    print(f"输出统计文件路径: {output_stats_path}")
    
    # 检查输入文件是否存在
    if not os.path.exists(input_path):
        print(f"错误: 输入文件 {input_path} 不存在")
        print("请先运行 test_merge_lives.2.0.py 生成输出文件")
        return
    
    try:
        # 读取输入文件
        with open(input_path, 'r', encoding='utf-8') as f:
            lives = json.load(f)
        
        print(f"\n成功读取输入文件，分组数: {len(lives)}")
        
        # 分析数据
        stats = analyze_lives_data(lives)
        
        # 转换为 M3U 格式
        print("\n=== 转换为 M3U 格式 ===")
        m3u_content = lives_to_m3u(lives)
        print(f"生成的 M3U 行数: {len(m3u_content.splitlines())}")
        
        # 写入 M3U 文件
        write_m3u_to_file(m3u_content, output_m3u_path)
        
        # 写入统计信息文件
        with open(output_stats_path, 'w', encoding='utf-8') as f:
            json.dump(stats, f, ensure_ascii=False, indent=2)
        print(f"统计信息已写入到: {output_stats_path}")
        
        print("\n=== 处理完成 ===")
        print(f"M3U 文件: {output_m3u_path}")
        print(f"统计文件: {output_stats_path}")
        
    except Exception as e:
        print(f"处理过程中出错: {str(e)}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
