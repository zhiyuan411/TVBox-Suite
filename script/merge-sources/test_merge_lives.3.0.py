#!/usr/bin/env python3
import json
import re

# 定义常量数组
CHANNEL_AGGREGATION_EXCLUDE_KEYWORDS = ['第']
CHANNEL_NAME_CLEAN_KEYWORDS = ['-']
GROUP_NAME_CLEAN_KEYWORDS = ['频道', '丨', '｜', '·', '-', '_', ';', '.', '📺', '☘️'
, '🏀', '🏛', '🎬', '🪁', '🇨🇳', '👠', '💋', '💃', '💝', '💖', '🍱', '🛰', '🔥', '🤹🏼'
, '🎼', '📛', '🐷', '🐻', '💰', '🎵', '🎮', '📡', '🕘️', '📢', '🎞', '🌊', '🇭🇰', '🇹🇼'
, '🇰🇷', '🎰', '🇯🇵', '📻', '🇺🇸', '🙏', '🌏', '🖥', '📽', '🔥', '🐬', '💰', '🆕']

# 定义调试URL数组
DEBUG_URLS = ['http://171.94.105.181:5000/rtp/239.10.0.105:5140']

# 定义分组名检查数组
GROUP_NAME_CHECK = ['北京']

# 定义频道名检查数组
CHANNEL_NAME_CHECK = ['IPTV武术']

# 输入文件路径
INPUT_FILE = '/Users/lizhiyuan/ideaWorkspace/TVBox-Suite/script/merge-sources/debug_valid_lives.json'


def is_debug_url(url):
    """
    检查URL是否包含调试URL数组中的元素
    :param url: URL字符串
    :return: 是否为调试URL
    """
    for debug_url in DEBUG_URLS:
        if debug_url in url:
            return True
    return False


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
    # 测试排除字段影响，直接全部返回false
    return False
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


def merge_lives_groups(lives):
    """
    合并 lives 数组中的重复分组和频道
    使用 URL 聚合并统计次数的算法
    :param lives: lives 数组
    :return: 合并后的 lives 数组
    """
    if not isinstance(lives, list):
        return []
    
    print(f"[Test] 开始处理 lives，共 {len(lives)} 个元素")
    
    # 1. 按 URL 聚合并统计次数
    url_to_group_stats = {}  # URL -> {分组名: 出现次数}
    url_to_channel_stats = {}  # URL -> {频道名: 出现次数}
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        
        # 清洗分组名
        original_group_name = group_item.get('group', '未分组')
        cleaned_group_name = clean_string(original_group_name, GROUP_NAME_CLEAN_KEYWORDS)
        
        # 检查分组名
        for check in GROUP_NAME_CHECK:
            if check in cleaned_group_name:
                print(f"[Check] 分组名包含 '{check}' - 原始: {original_group_name}, 清洗后: {cleaned_group_name}")
                break
        
        channels = group_item.get('channels', [])
        
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            
            original_channel_name = channel_item.get('name', '未命名')
            # 清洗频道名（对所有情况都生效）
            cleaned_channel_name = clean_string(original_channel_name, CHANNEL_NAME_CLEAN_KEYWORDS)
            
            # 检查频道名
            for check in CHANNEL_NAME_CHECK:
                if check in cleaned_channel_name:
                    print(f"[Check] 频道名包含 '{check}' - 原始: {original_channel_name}, 清洗后: {cleaned_channel_name}, 分组(原始/清洗): {original_group_name}/{cleaned_group_name}")
                    break
            
            urls = channel_item.get('urls', [])
            
            for url in urls:
                if not url:
                    continue
                
                # 调试信息
                if is_debug_url(url):
                    print(f"[Debug] URL: {url} - 原始分组: {original_group_name}, 清洗后分组: {cleaned_group_name}, 原始频道: {original_channel_name}, 清洗后频道: {cleaned_channel_name}")
                
                # 更新分组统计
                if url not in url_to_group_stats:
                    url_to_group_stats[url] = {}
                # 对所有情况都使用清洗后的分组名
                url_to_group_stats[url][cleaned_group_name] = url_to_group_stats[url].get(cleaned_group_name, 0) + 1
                
                # 更新频道统计
                if url not in url_to_channel_stats:
                    url_to_channel_stats[url] = {}
                url_to_channel_stats[url][cleaned_channel_name] = url_to_channel_stats[url].get(cleaned_channel_name, 0) + 1
    
    print(f"[Test] URL 聚合完成，共 {len(url_to_group_stats)} 个唯一 URL")
    
    # 2. 为每个 URL 选择出现次数最多的分组和频道
    url_to_best_match = {}
    for url, group_stats in url_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_stats = url_to_channel_stats.get(url, {})
        best_channel = get_most_frequent(channel_stats)
        
        # 调试信息
        if is_debug_url(url):
            print(f"[Debug] URL: {url} - 分组统计: {group_stats}, 选择分组: {best_group}, 频道统计: {channel_stats}, 选择频道: {best_channel}")
        
        url_to_best_match[url] = (best_group, best_channel)
    
    print(f"[Test] URL 匹配完成，共 {len(url_to_best_match)} 个匹配结果")
    
    # 3. 按照频道聚合统计分组次数，合并频道并归入次数最多的分组
    channel_to_group_stats = {}
    channel_to_urls = {}
    excluded_channels = []  # 存储应排除聚合的频道 [{'channel': channel_name, 'group': group_name, 'urls': [url1, url2, ...]}]
    
    for url, (group, channel) in url_to_best_match.items():
        # 检查是否应排除在聚合之外
        should_exclude = should_exclude_from_aggregation(channel)
        
        # 调试信息
        if is_debug_url(url):
            print(f"[Debug] URL: {url} - 分组: {group}, 频道: {channel}, 是否排除聚合: {should_exclude}")
        
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
                    # 调试信息
                    if is_debug_url(url):
                        print(f"[Debug] URL: {url} - 已添加到现有记录")
            else:
                # 如果不存在，创建新记录
                excluded_channels.append({'channel': channel, 'group': group, 'urls': [url]})
                # 调试信息
                if is_debug_url(url):
                    print(f"[Debug] URL: {url} - 已创建新记录")
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
                # 调试信息
                if is_debug_url(url):
                    print(f"[Debug] URL: {url} - 已添加到频道: {channel}")
    
    print(f"[Test] 频道聚合完成，共 {len(channel_to_group_stats)} 个频道，{len(excluded_channels)} 个排除聚合的频道")
    
    # 为每个频道选择出现次数最多的分组
    channel_to_best_group = {}
    for channel, group_stats in channel_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_to_best_group[channel] = best_group
        
        # 调试信息
        if any(is_debug_url(url) for url in channel_to_urls.get(channel, [])):
            print(f"[Debug] 频道: {channel} - 分组统计: {group_stats}, 选择分组: {best_group}")
    
    print(f"[Test] 频道分组分配完成，共 {len(channel_to_best_group)} 个频道分配到分组")
    
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
                # 调试信息
                if is_debug_url(url):
                    print(f"[Debug] URL: {url} - 已添加到分组: {group}, 频道: {channel}")
    
    print(f"[Test] 分组-频道-URL 结构构建完成，共 {len(group_channel_map)} 个分组")
    
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
                # 调试信息
                if is_debug_url(url):
                    print(f"[Debug] URL: {url} - 已移动到单剧分组")
    
    print(f"[Test] 单剧分组处理完成，共移动 {len(channels_to_move)} 个频道到单剧分组")
    
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
            
            # 调试信息
            if any(is_debug_url(url) for url in urls):
                print(f"[Debug] 分组: {group_name}, 频道: {channel_name}, URL数量: {len(urls)}")
        
        merged_lives.append({
            'group': group_name,
            'channels': merged_channels
        })
    
    print(f"[Test] 分组排序完成，共 {len(merged_lives)} 个分组")
    return merged_lives


def main():
    """
    主函数，测试 merge_lives_groups 函数
    """
    try:
        # 读取输入文件
        with open(INPUT_FILE, 'r', encoding='utf-8') as f:
            lives = json.load(f)
        
        print(f"[Test] 成功读取输入文件，共 {len(lives)} 个元素")
        
        # 调用 merge_lives_groups 函数
        merged_lives = merge_lives_groups(lives)
        
        # 输出统计信息
        print(f"[Test] 合并完成，共 {len(merged_lives)} 个分组")
        
        total_channels = 0
        total_urls = 0
        for group in merged_lives:
            channel_count = len(group.get('channels', []))
            url_count = sum(len(channel.get('urls', [])) for channel in group.get('channels', []))
            total_channels += channel_count
            total_urls += url_count
            print(f"[Test] 分组: {group.get('group')}, 频道数: {channel_count}, URL数: {url_count}")
        
        print(f"[Test] 总计: {total_channels} 个频道, {total_urls} 个 URL")
        
        # 保存输出文件
        output_file = 'test_output.json'
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(merged_lives, f, ensure_ascii=False, indent=2)
        
        print(f"[Test] 输出已保存到: {output_file}")
        
        # 保存仅包含分组名和频道名的文件（去除urls数组）
        # 组织成自定义文本格式，每行显示一个分组及其所有频道
        stripped_output_file = 'test_output_stripped.txt'
        with open(stripped_output_file, 'w', encoding='utf-8') as f:
            for group in merged_lives:
                group_name = group.get('group')
                channels = group.get('channels', [])
                channel_names = []
                for channel in channels:
                    channel_name = channel.get('name')
                    if channel_name not in channel_names:
                        channel_names.append(channel_name)
                if channel_names:
                    channels_str = '，'.join(channel_names)
                    f.write(f"👉 {group_name}：{channels_str}\n")
        
        print(f"[Test] 仅包含分组名和频道名的输出已保存到: {stripped_output_file}")
        
    except Exception as e:
        print(f"[Error] 测试过程中发生错误: {str(e)}")


if __name__ == '__main__':
    main()
