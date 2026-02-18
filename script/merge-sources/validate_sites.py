#!/usr/bin/env python3
import json
import sys
from pathlib import Path

def validate_sites(json_file):
    """
    验证tv.json文件中的sites字段格式是否正确
    :param json_file: JSON文件路径
    :return: 布尔值，表示验证是否通过
    """
    try:
        # 读取文件
        with open(json_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        print(f"验证文件: {json_file}")
        print("=" * 60)
        
        # 获取sites数组
        if 'video' in data and 'sites' in data['video']:
            sites = data['video']['sites']
            print("从 video.sites 获取站点配置")
        elif 'sites' in data:
            sites = data['sites']
            print("从 sites 获取站点配置")
        else:
            print("❌ 错误: 未找到 sites 或 video.sites 字段")
            return False
        
        # 验证sites是否为数组
        if not isinstance(sites, list):
            print("❌ 错误: sites 不是数组类型")
            return False
        
        print(f"找到 {len(sites)} 个站点")
        print("=" * 60)
        
        # 验证每个站点
        valid_count = 0
        invalid_count = 0
        valid_sites = []
        invalid_sites = []
        
        for i, site in enumerate(sites):
            print(f"验证站点 {i+1}:")
            
            # 验证站点是否为字典
            if not isinstance(site, dict):
                print(f"  ❌ 错误: 站点不是字典类型")
                invalid_sites.append({"index": i+1, "site": site, "error": "站点不是字典类型"})
                invalid_count += 1
                continue
            
            # 验证必需字段
            required_fields = ['key', 'name', 'type', 'api']
            missing_fields = []
            
            for field in required_fields:
                if field not in site:
                    missing_fields.append(field)
                else:
                    # 验证字段类型
                    if field == 'key' and not isinstance(site[field], str):
                        print(f"  ❌ 错误: {field} 不是字符串类型")
                        missing_fields.append(field)
                    elif field == 'name' and not isinstance(site[field], str):
                        print(f"  ❌ 错误: {field} 不是字符串类型")
                        missing_fields.append(field)
                    elif field == 'type' and not isinstance(site[field], int):
                        print(f"  ❌ 错误: {field} 不是整数类型")
                        missing_fields.append(field)
                    elif field == 'api' and not isinstance(site[field], str):
                        print(f"  ❌ 错误: {field} 不是字符串类型")
                        missing_fields.append(field)
                    elif field in ['key', 'name', 'api'] and not site[field].strip():
                        print(f"  ❌ 错误: {field} 为空字符串")
                        missing_fields.append(field)
            
            if missing_fields:
                error_msg = f"缺少或类型错误的必需字段: {', '.join(missing_fields)}"
                print(f"  ❌ 错误: {error_msg}")
                invalid_sites.append({"index": i+1, "site": site, "error": error_msg})
                invalid_count += 1
            else:
                print(f"  ✅ 站点验证通过: {site.get('name', '未知')} (key: {site.get('key', '未知')})")
                valid_sites.append(site)
                valid_count += 1
            
            print()
        
        print("=" * 60)
        print("验证结果汇总:")
        print(f"总站点数: {len(sites)}")
        print(f"有效站点: {valid_count}")
        print(f"无效站点: {invalid_count}")
        
        # 汇总错误站点
        if invalid_sites:
            print("\n" + "=" * 60)
            print("错误站点汇总:")
            print("=" * 60)
            for invalid in invalid_sites:
                print(f"站点 {invalid['index']}: {invalid['error']}")
                print(f"  原始配置: {json.dumps(invalid['site'], ensure_ascii=False)}")
                print()
        
        # 生成修正后的配置文件
        if valid_sites:
            print("\n" + "=" * 60)
            print("生成修正后的配置文件:")
            print("=" * 60)
            
            # 创建修正后的配置
            corrected_data = data.copy()
            
            # 更新sites数组
            if 'video' in corrected_data and 'sites' in corrected_data['video']:
                corrected_data['video']['sites'] = valid_sites
            elif 'sites' in corrected_data:
                corrected_data['sites'] = valid_sites
            
            # 写入修正后的文件
            corrected_file = Path("tv.json.corrected")
            try:
                with open(corrected_file, 'w', encoding='utf-8') as f:
                    json.dump(corrected_data, f, indent=4, ensure_ascii=False)
                print(f"✅ 修正后的配置文件已生成: {corrected_file}")
                print(f"   包含 {len(valid_sites)} 个有效站点")
            except Exception as e:
                print(f"❌ 生成修正文件失败: {e}")
        
        if invalid_count == 0:
            print("\n✅ 所有站点验证通过!")
            return True
        else:
            print("\n❌ 存在无效站点，请检查")
            return False
            
    except json.JSONDecodeError as e:
        print(f"❌ JSON解析错误: {e}")
        return False
    except Exception as e:
        print(f"❌ 验证过程中出错: {e}")
        return False

if __name__ == "__main__":
    # 默认验证tv.json文件
    json_file = Path("tv.json")
    
    # 如果提供了命令行参数，使用指定的文件
    if len(sys.argv) > 1:
        json_file = Path(sys.argv[1])
    
    # 检查文件是否存在
    if not json_file.exists():
        print(f"❌ 错误: 文件 {json_file} 不存在")
        sys.exit(1)
    
    # 执行验证
    success = validate_sites(json_file)
    sys.exit(0 if success else 1)

