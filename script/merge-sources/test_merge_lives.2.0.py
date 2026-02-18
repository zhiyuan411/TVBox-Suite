#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试 merge_lives_groups 函数的独立脚本
目标：处理 ../../web/tv.json 文件，打印统计日志，输出处理后的 lives 字段
"""

import json
import os
import re
from urllib.parse import urlparse


def get_most_frequent(item_dict):
    """
    获取字典中值最大的键
    :param item_dict: 键值对字典
    :return: 出现次数最多的键
    """
    if not item_dict:
        return "未分组"
    return max(item_dict.items(), key=lambda x: x[1])[0]


def merge_lives_groups(lives):
    """
    合并 lives 数组中的重复分组和频道
    使用 URL 聚合并统计次数的算法
    :param lives: lives 数组
    :return: 合并后的 lives 数组
    """
    if not isinstance(lives, list):
        return []
    
    print(f"步骤1: 开始处理，原始 lives 数组长度: {len(lives)}")
    
    # 统计原始数据量
    original_url_count = 0
    original_channel_count = 0
    original_group_count = len(lives)
    
    for group_item in lives:
        if not isinstance(group_item, dict):
            continue
        channels = group_item.get('channels', [])
        original_channel_count += len(channels)
        for channel_item in channels:
            if not isinstance(channel_item, dict):
                continue
            urls = channel_item.get('urls', [])
            original_url_count += len(urls)
    
    print(f"原始数据统计: 分组数={original_group_count}, 频道数={original_channel_count}, URL数={original_url_count}")
    
    # 1. 按 URL 聚合并统计次数
    url_to_group_stats = {}  # URL -> {分组名: 出现次数}
    url_to_channel_stats = {}  # URL -> {频道名: 出现次数}
    
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
                
                # 更新分组统计
                if url not in url_to_group_stats:
                    url_to_group_stats[url] = {}
                url_to_group_stats[url][group_name] = url_to_group_stats[url].get(group_name, 0) + 1
                
                # 更新频道统计
                if url not in url_to_channel_stats:
                    url_to_channel_stats[url] = {}
                url_to_channel_stats[url][channel_name] = url_to_channel_stats[url].get(channel_name, 0) + 1
    
    print(f"步骤1完成: 唯一 URL 数={len(url_to_group_stats)}")
    
    # 2. 为每个 URL 选择出现次数最多的分组和频道
    url_to_best_match = {}
    for url, group_stats in url_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_stats = url_to_channel_stats.get(url, {})
        best_channel = get_most_frequent(channel_stats)
        url_to_best_match[url] = (best_group, best_channel)
    
    print(f"步骤2完成: URL 映射完成，映射数={len(url_to_best_match)}")
    
    # 3. 按照频道聚合统计分组次数，合并频道并归入次数最多的分组
    channel_to_group_stats = {}
    channel_to_urls = {}
    
    for url, (group, channel) in url_to_best_match.items():
        # 统计频道的分组次数
        if channel not in channel_to_group_stats:
            channel_to_group_stats[channel] = {}
        channel_to_group_stats[channel][group] = channel_to_group_stats[channel].get(group, 0) + 1
        
        # 收集频道的所有 URL
        if channel not in channel_to_urls:
            channel_to_urls[channel] = []
        if url not in channel_to_urls[channel]:
            channel_to_urls[channel].append(url)
    
    print(f"步骤3: 频道聚合完成，唯一频道数={len(channel_to_group_stats)}")
    
    # 为每个频道选择出现次数最多的分组
    channel_to_best_group = {}
    for channel, group_stats in channel_to_group_stats.items():
        best_group = get_most_frequent(group_stats)
        channel_to_best_group[channel] = best_group
    
    print(f"步骤3完成: 频道分组映射完成")
    
    # 4. 构建分组-频道-URL 的结构
    group_channel_map = {}
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
    
    print(f"步骤4完成: 分组-频道-URL 结构构建完成，分组数={len(group_channel_map)}")
    
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
    
    print(f"步骤5: 识别到需要移动到'单剧'分组的频道数={len(channels_to_move)}")
    
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
    
    print(f"步骤5完成: '单剧'分组处理完成，当前分组数={len(group_channel_map)}")
    
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
    
    final_url_count = 0
    final_channel_count = 0
    final_group_count = 0
    
    for group_name, channels, channel_count, url_count, ratio in sorted_groups:
        # 跳过空分组
        if not channels:
            continue
        
        final_group_count += 1
        final_channel_count += channel_count
        final_url_count += url_count
        
        # 按照频道名顺向排序
        sorted_channels = sorted(channels.items(), key=lambda x: x[0])
        
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
    
    print(f"步骤6完成: 排序完成")
    print(f"最终数据统计: 分组数={final_group_count}, 频道数={final_channel_count}, URL数={final_url_count}")
    
    # 计算压缩率（添加除零检查）
    group_compression = 0
    if original_group_count > 0:
        group_compression = 100 - (final_group_count/original_group_count*100)
    
    channel_compression = 0
    if original_channel_count > 0:
        channel_compression = 100 - (final_channel_count/original_channel_count*100)
    
    url_compression = 0
    if original_url_count > 0:
        url_compression = 100 - (final_url_count/original_url_count*100)
    
    print(f"数据压缩率: 分组={group_compression:.2f}%, 频道={channel_compression:.2f}%, URL={url_compression:.2f}%")
    
    return merged_lives


def extract_domain(url):
    """
    提取URL的域名
    :param url: URL字符串
    :return: 域名字符串，提取失败返回空字符串
    """
    try:
        # 处理特殊情况，如IPV6地址
        if url.startswith('['):
            # IPV6地址格式: [2001:db8::1]:8080/path
            match = re.match(r'\[([^\]]+)\](?::\d+)?', url)
            if match:
                return match.group(1)
        
        # 标准URL格式
        parsed = urlparse(url)
        if parsed.netloc:
            # 移除端口号
            domain = parsed.netloc.split(':')[0]
            return domain
        else:
            # 没有协议的URL，尝试从路径中提取
            match = re.match(r'^([^/]+)', url)
            if match:
                return match.group(1)
    except Exception:
        pass
    return ""


class DNSValidator:
    """
    DNS域名验证器，使用DNS池子循环测试
    """
    
    # 国内DNS服务器列表（按响应时间排序）
    COMMON_DNS_SERVERS = [
        "223.6.6.6",    # 阿里
        "223.5.5.5",    # 阿里
        "123.123.123.123", # 中国联通DNS
        "123.123.123.124", # 中国联通DNS（备用）
        "210.2.4.8",    # CNNIC（备用）
        "180.184.2.2",  # 字节（火山引擎）
        "180.184.1.1",  # 字节（火山引擎）
        "1.2.4.8",      # CNNIC
        "119.28.28.28", # 腾讯
        "180.76.76.76", # 百度
        "119.29.29.29", # 腾讯
    ]
    
    def __init__(self, timeout=2):
        """
        初始化DNS验证器
        :param timeout: DNS查询超时时间（秒）
        """
        self.timeout = timeout
        self.dns_pool = []
        self.current_index = 0
        self.initialize_dns_pool()
    
    def initialize_dns_pool(self):
        """
        初始化DNS池子，验证并筛选可用的DNS服务器
        """
        print("正在初始化DNS池子...")
        
        for dns_server in self.COMMON_DNS_SERVERS:
            if self._test_dns_server(dns_server):
                self.dns_pool.append(dns_server)
                print(f"DNS服务器 {dns_server} 可用")
            else:
                print(f"DNS服务器 {dns_server} 不可用")
        
        if not self.dns_pool:
            print("警告: 没有可用的DNS服务器，将使用系统默认DNS")
        else:
            print(f"DNS池子初始化完成，可用DNS服务器数量: {len(self.dns_pool)}")
    
    def _test_dns_server(self, dns_server):
        """
        测试单个DNS服务器是否可用
        :param dns_server: DNS服务器IP
        :return: 是否可用
        """
        import subprocess
        import platform
        
        try:
            # 使用nslookup或dig测试DNS服务器
            if platform.system() == "Windows":
                # Windows系统使用nslookup
                cmd = ["nslookup", "example.com", dns_server]
                result = subprocess.run(cmd, timeout=self.timeout, capture_output=True, text=True)
                return result.returncode == 0 and "Non-authoritative answer" in result.stdout
            else:
                # Unix/Linux系统使用dig
                cmd = ["dig", "@" + dns_server, "example.com", "+short"]
                result = subprocess.run(cmd, timeout=self.timeout, capture_output=True, text=True)
                return result.returncode == 0 and len(result.stdout.strip()) > 0
        except Exception:
            return False
    
    def _get_next_dns(self):
        """
        获取下一个DNS服务器（循环使用）
        :return: DNS服务器IP
        """
        if not self.dns_pool:
            return None
        
        dns_server = self.dns_pool[self.current_index]
        self.current_index = (self.current_index + 1) % len(self.dns_pool)
        return dns_server
    
    def is_domain_valid(self, domain):
        """
        测试域名或IP地址是否有效
        :param domain: 域名或IP地址
        :return: (是否有效, 使用的DNS服务器, 错误信息)
        """
        if not domain:
            return False, None, "空域名/IP"
        
        # 检查是否为IP地址
        if self._is_ip_address(domain):
            return self._test_ip_validity(domain)
        
        # 测试最多3个不同的DNS服务器
        tested_dns = set()
        
        for _ in range(3):
            dns_server = self._get_next_dns()
            
            # 如果没有可用DNS或已测试过所有DNS
            if not dns_server or dns_server in tested_dns:
                break
            
            tested_dns.add(dns_server)
            
            try:
                # 使用指定DNS服务器解析域名
                import subprocess
                import platform
                
                if platform.system() == "Windows":
                    cmd = ["nslookup", domain, dns_server]
                    result = subprocess.run(cmd, timeout=self.timeout, capture_output=True, text=True)
                    if result.returncode == 0 and ("Non-authoritative answer" in result.stdout or "Address:" in result.stdout):
                        return True, dns_server, "DNS解析成功"
                else:
                    cmd = ["dig", "@" + dns_server, domain, "+short"]
                    result = subprocess.run(cmd, timeout=self.timeout, capture_output=True, text=True)
                    if result.returncode == 0 and len(result.stdout.strip()) > 0:
                        return True, dns_server, "DNS解析成功"
                        
            except Exception as e:
                continue
        
        # 如果没有可用DNS，尝试系统默认DNS
        if not tested_dns:
            try:
                import socket
                socket.gethostbyname(domain)
                return True, "系统默认", "系统DNS解析成功"
            except Exception:
                pass
        
        return False, None, "DNS解析失败"
    
    def _is_ip_address(self, address):
        """
        判断输入是否为IP地址（IPv4或IPv6）
        :param address: 输入字符串
        :return: 是否为IP地址
        """
        import socket
        
        # 测试IPv4
        try:
            socket.inet_pton(socket.AF_INET, address)
            return True
        except socket.error:
            pass
        
        # 测试IPv6
        try:
            socket.inet_pton(socket.AF_INET6, address)
            return True
        except socket.error:
            pass
        
        return False
    
    def _test_ip_validity(self, ip):
        """
        快速测试IP地址的有效性
        :param ip: IP地址字符串
        :return: (是否有效, 使用的方法, 错误信息)
        """
        import socket
        
        # 检查是否为保留地址或特殊地址
        reserved_ranges = [
            # IPv4 保留地址范围
            ("10.0.0.0", "10.255.255.255"),       # 私有网络
            ("172.16.0.0", "172.31.255.255"),     # 私有网络
            ("192.168.0.0", "192.168.255.255"),   # 私有网络
            ("127.0.0.0", "127.255.255.255"),     # 环回地址
            ("169.254.0.0", "169.254.255.255"),   # 链路本地地址
            ("0.0.0.0", "0.255.255.255"),         # 网络地址
            ("224.0.0.0", "239.255.255.255"),     # 多播地址
            ("240.0.0.0", "255.255.255.255"),     # 保留地址
        ]
        
        # 检查IPv4保留地址
        if "." in ip:
            try:
                ip_int = self._ip_to_int(ip)
                for start, end in reserved_ranges:
                    start_int = self._ip_to_int(start)
                    end_int = self._ip_to_int(end)
                    if start_int <= ip_int <= end_int:
                        return False, "IP验证", f"IP地址 {ip} 是保留地址"
            except Exception:
                return False, "IP验证", f"IP地址 {ip} 格式无效"
        
        # 快速测试IP是否可访问（设置短超时）
        try:
            # 创建socket对象
            sock = socket.socket(socket.AF_INET if "." in ip else socket.AF_INET6, socket.SOCK_STREAM)
            # 设置超时时间为0.5秒
            sock.settimeout(0.5)
            # 尝试连接到常用端口（80或443）
            ports = [80, 443]
            for port in ports:
                try:
                    # 只进行连接尝试，不发送数据
                    sock.connect((ip, port))
                    sock.close()
                    return True, "IP验证", f"IP地址 {ip} 可访问"
                except socket.timeout:
                    # 超时，尝试下一个端口
                    continue
                except Exception:
                    # 其他错误，尝试下一个端口
                    continue
            sock.close()
            # 如果所有端口都失败，返回格式有效但不可访问
            return True, "IP验证", f"IP地址 {ip} 格式有效但可能不可访问"
        except Exception as e:
            return False, "IP验证", f"IP地址 {ip} 测试失败: {str(e)}"
    
    def _ip_to_int(self, ip):
        """
        将IPv4地址转换为整数
        :param ip: IPv4地址字符串
        :return: 整数值
        """
        import socket
        return int.from_bytes(socket.inet_aton(ip), byteorder='big')



def main():
    """
    主函数：读取 tv.json 文件，处理 lives 字段，输出结果
    """
    # 构建输入文件路径
    input_path = os.path.join(os.path.dirname(__file__), "lives.single.json")
    output_path = os.path.join(os.path.dirname(__file__), "output_lives.json")
    
    print(f"输入文件路径: {input_path}")
    print(f"输出文件路径: {output_path}")
    
    # 检查输入文件是否存在
    if not os.path.exists(input_path):
        print(f"错误: 输入文件 {input_path} 不存在")
        return
    
    try:
        # 读取输入文件
        with open(input_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # 提取 lives 字段（如果文件直接是 lives 数组，则直接使用）
        if isinstance(data, list):
            lives = data
        else:
            lives = data.get('lives', [])
        print(f"成功读取 lives 字段，长度: {len(lives)}")
        
        # 处理 lives 字段
        merged_lives = merge_lives_groups(lives)
        
        # 输出处理后的 lives 字段
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(merged_lives, f, ensure_ascii=False, indent=2)
        
        print(f"\n处理完成！")
        print(f"处理后的 lives 字段已保存到: {output_path}")
        print(f"合并后的分组数: {len(merged_lives)}")
        
        # 打印所有分组的信息
        print("\n所有分组信息:")
        total_channels = 0
        total_urls = 0
        domain_set = set()
        
        for i, group in enumerate(merged_lives):
            group_name = group.get('group', '未知')
            channels = group.get('channels', [])
            channel_count = len(channels)
            url_count = sum(len(channel.get('urls', [])) for channel in channels)
            total_channels += channel_count
            total_urls += url_count
            
            # 提取并统计所有URL的域名
            for channel in channels:
                urls = channel.get('urls', [])
                for url in urls:
                    domain = extract_domain(url)
                    if domain:
                        domain_set.add(domain)
            
            # 获取前3个频道的名称
            top_channels = [channel.get('name', '未知') for channel in channels[:3]]
            channel_names = ', '.join(top_channels)
            
            print(f"{i+1}. {group_name}：频道总数{channel_count}，URL总数{url_count}，前3个频道：{channel_names}")
        
        # 打印所有分组的统计信息
        print("\n所有分组统计信息:")
        print(f"总分组数: {len(merged_lives)}")
        print(f"总频道数: {total_channels}")
        print(f"总URL数: {total_urls}")
        print(f"去重后域名数: {len(domain_set)}")
        
        # 打印前10个域名作为示例
        if domain_set:
            print("\n前10个域名示例:")
            for i, domain in enumerate(list(domain_set)[:10]):
                print(f"{i+1}. {domain}")
            
            # 测试域名有效性
            print("\n测试域名有效性...")
            dns_validator = DNSValidator()
            
            # 测试所有域名
            test_domains = list(domain_set)
            valid_count = 0
            invalid_count = 0
            
            print("\n域名测试结果:")
            for i, domain in enumerate(test_domains):
                valid, dns_server, message = dns_validator.is_domain_valid(domain)
                status = "有效" if valid else "无效"
                
                if valid:
                    valid_count += 1
                else:
                    invalid_count += 1
                
                print(f"{i+1}. 域名: {domain}, 状态: {status}, DNS: {dns_server}, 信息: {message}")
            
            # 打印测试统计
            print("\n域名测试统计:")
            print(f"测试域名总数: {len(test_domains)}")
            print(f"有效域名数: {valid_count}")
            print(f"无效域名数: {invalid_count}")
            if test_domains:
                print(f"有效率: {valid_count/len(test_domains)*100:.2f}%")
            else:
                print("有效率: 0.00%")
            
    except Exception as e:
        print(f"处理过程中出错: {str(e)}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
