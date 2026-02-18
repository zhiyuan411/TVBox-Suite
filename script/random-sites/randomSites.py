#! /usr/bin/env python3

import json
import random
import os
from datetime import datetime

# 获取当前时间戳并格式化为字符串
def get_current_timestamp():
    return datetime.now().strftime('%Y-%m-%d %H:%M:%S')

# Step 1: Count occurrences of "tv.json" in the access log
def count_tv_json_occurrences(log_path):
    with open(log_path, 'r') as file:
        count = sum('tv.json' in line for line in file)
        #print(f"[{get_current_timestamp()}] Found {count} occurrences of 'tv.json' in the access log.")
        return count

# Step 2: Compare and update the count if necessary
def compare_and_update_count(count, counts_file_path):
    try:
        with open(counts_file_path, 'r+') as file:
            old_count = int(file.read().strip())
            if old_count == count:
                #print(f"[{get_current_timestamp()}] Counts are equal, exiting.")
                exit(0)
            else:
                file.seek(0)
                file.write(str(count))
                file.truncate()
                print(f"[{get_current_timestamp()}] Updated count to {count}")
    except FileNotFoundError:
        with open(counts_file_path, 'w') as file:
            file.write(str(count))
            print(f"[{get_current_timestamp()}] Created new count file with value {count}")

# Step 3: Read whitelist and blacklist (修改：返回列表保留顺序，而非集合)
def read_list(file_path):
    try:
        with open(file_path, 'r') as file:
            # 保留非空行，且strip后返回列表（维持文件中的原始顺序）
            return [line.strip() for line in file if line.strip()]
    except FileNotFoundError:
        print(f"[{get_current_timestamp()}] File not found: {file_path}. Using empty list.")
        return []

# Step 4 & 5: Process tv.json (核心修改：白名单站点按关键词顺序排序)
def process_tv_json(tv_json_path, whitelist, blacklist):
    try:
        with open(tv_json_path, 'r') as file:
            data = json.load(file)

        sites = data.get('sites', [])
        
        # 辅助函数：获取站点匹配的第一个白名单关键词的索引（用于排序）
        def get_whitelist_priority(site):
            site_key = site.get('key', '')
            site_name = site.get('name', '')
            # 遍历白名单关键词（按原始顺序），找到第一个匹配的关键词索引
            for idx, word in enumerate(whitelist):
                if word in site_key or word in site_name:
                    return idx
            # 未匹配到则返回极大值（理论上不会走到这，因为只处理白名单站点）
            return float('inf')
        
        # 1. 筛选白名单站点（匹配任意白名单关键词）
        whitelist_sites = [
            site for site in sites 
            if any(word in site.get('key', '') or word in site.get('name', '') for word in whitelist)
        ]
        
        # 2. 按白名单关键词的出现顺序排序白名单站点（核心修改）
        whitelist_sites.sort(key=get_whitelist_priority)
        
        # 3. 筛选黑名单站点（逻辑不变）
        blacklist_sites = [
            site for site in sites 
            if any(word in site.get('key', '') or word in site.get('name', '') for word in blacklist)
        ]
        
        # 4. 筛选其他站点（逻辑不变）
        other_sites = [site for site in sites if site not in whitelist_sites + blacklist_sites]

        # 创建一个包含所有黑名单站点 key 的集合
        blacklist_keys = {site['key'] for site in blacklist_sites}

        # 过滤 whitelist_sites，仅保留那些 key 不在 blacklist_keys 中的站点
        whitelist_sites = [
            site for site in whitelist_sites
            if site['key'] not in blacklist_keys
        ]

        # Shuffle non-whitelist and non-blacklist sites
        random.shuffle(other_sites)

        # Reorder sites: whitelist first (按关键词顺序), then others, finally blacklist
        reordered_sites = whitelist_sites + other_sites + blacklist_sites
        data['sites'] = reordered_sites

        with open(tv_json_path, 'w') as file:
            json.dump(data, file, indent=4, ensure_ascii=False)

        print(f"[{get_current_timestamp()}] TV JSON file processed and saved.")

    except Exception as e:
        print(f"[{get_current_timestamp()}] Error processing TV JSON file: {e}")

if __name__ == "__main__":
    access_log_path = '/var/log/nginx/access.log'
    counts_file_path = './tv-counts.txt'
    whitelist_path = './whitelist.txt'
    blacklist_path = './blacklist.txt'
    tv_json_path = '../../web/tv.json'

    tv_json_count = count_tv_json_occurrences(access_log_path)
    compare_and_update_count(tv_json_count, counts_file_path)

    # 读取白/黑名单（现在返回列表，保留顺序）
    whitelist = read_list(whitelist_path)
    blacklist = read_list(blacklist_path)

    process_tv_json(tv_json_path, whitelist, blacklist)
