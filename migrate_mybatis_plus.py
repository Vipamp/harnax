#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MyBatis-Plus 自动迁移脚本
将所有 Mapper、Entity、ServiceImpl 从 MyBatis-Plus 迁移到纯 MyBatis + PageHelper

使用方法:
    python3 migrate_mybatis_plus.py

作者: vipamp
日期: 2026-04-19
"""

import os
import re
from pathlib import Path

# 项目根目录
PROJECT_ROOT = Path(__file__).parent
ADMIN_DIR = PROJECT_ROOT / "vipclaw-admin" / "src" / "main" / "kotlin" / "com" / "vipamp" / "vipclaw" / "admin"

# 统计信息
stats = {
    "mappers": 0,
    "entities": 0,
    "services": 0,
    "errors": []
}

def read_file(file_path):
    """读取文件内容"""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            return f.read()
    except Exception as e:
        print(f"❌ 读取文件失败: {file_path}, 错误: {e}")
        return None

def write_file(file_path, content):
    """写入文件内容"""
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        return True
    except Exception as e:
        print(f"❌ 写入文件失败: {file_path}, 错误: {e}")
        return False

def extract_table_name(entity_name):
    """从实体类名推断表名 (驼峰转下划线)"""
    # Agent -> agent, McpServer -> mcp_server
    s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', entity_name)
    return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

def extract_entity_fields(content):
    """提取实体类字段信息"""
    fields = []
    # 匹配 var fieldName: Type = defaultValue
    pattern = r'var\s+(\w+):\s+(\w+(?:<[^>]+>)?)\s*=\s*([^\\n]+)'
    matches = re.findall(pattern, content)
    for field_name, field_type, default_value in matches:
        # 跳过 companion object 和 serialVersionUID
        if field_name in ['companion', 'serialVersionUID']:
            continue
        fields.append({
            'name': field_name,
            'type': field_type,
            'default': default_value.strip()
        })
    return fields

def field_to_column(field_name):
    """驼峰转下划线: modelName -> model_name"""
    s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', field_name)
    return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

def migrate_mapper(file_path):
    """迁移 Mapper 文件"""
    print(f"  📝 处理 Mapper: {file_path.name}")
    
    content = read_file(file_path)
    if not content:
        return False
    
    # 提取实体名
    entity_match = re.search(r'interface\s+\w+Mapper\s*:\s*BaseMapper<(\w+)>', content)
    if not entity_match:
        # 可能已经迁移过了
        if 'BaseMapper' not in content:
            print(f"    ⏭️  已迁移,跳过")
            return True
        print(f"    ⚠️  无法提取实体名")
        return False
    
    entity_name = entity_match.group(1)
    table_name = extract_table_name(entity_name)
    
    # 提取字段
    entity_file = ADMIN_DIR / "entity" / f"{entity_name}.kt"
    if not entity_file.exists():
        print(f"    ⚠️  实体文件不存在: {entity_file}")
        return False
    
    entity_content = read_file(entity_file)
    fields = extract_entity_fields(entity_content)
    
    # 构建 CRUD SQL
    insert_fields = [f for f in fields if f['name'] != 'id']
    insert_columns = ', '.join([field_to_column(f['name']) for f in insert_fields])
    insert_values = ', '.join([f"#{{{f['name']}}}" for f in insert_fields])
    
    update_fields = [f for f in fields if f['name'] not in ['id', 'createTime']]
    update_set = ',\n            '.join([f"{field_to_column(f['name'])} = #{{{f['name']}}}" for f in update_fields])
    
    # 生成新的 Mapper 内容
    new_content = f'''package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.{entity_name}
import org.apache.ibatis.annotations.*

/**
 * {entity_name} Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface {entity_name}Mapper {{

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM {table_name} WHERE id = #{{id}} LIMIT 1")
    fun selectById(@Param("id") id: Long): {entity_name}?

    @Insert(
        """
        INSERT INTO {table_name} (
            {insert_columns}
        ) VALUES (
            {insert_values}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert({entity_name.lower()}: {entity_name}): Int

    @Update(
        """
        UPDATE {table_name} SET
            {update_set}
        WHERE id = #{{id}}
        """
    )
    fun updateById({entity_name.lower()}: {entity_name}): Int

    @Update("UPDATE {table_name} SET active = 0 WHERE id = #{{id}}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
'''
    
    # 保留原有的自定义查询方法
    # 提取 interface 中 BaseMapper 之后的内容
    custom_methods_match = re.search(r'interface\s+\w+Mapper\s*:\s*BaseMapper<\w+>\s*\{(.+?)\n\}', content, re.DOTALL)
    if custom_methods_match:
        custom_methods = custom_methods_match.group(1).strip()
        # 移除已有的 selectActiveById 等重复方法
        if '@Select("SELECT * FROM' not in custom_methods or 'WHERE id = #{id} LIMIT 1' not in custom_methods:
            new_content += custom_methods + '\n'
    
    new_content += '}\n'
    
    # 写入文件
    if write_file(file_path, new_content):
        print(f"    ✅ Mapper 迁移完成")
        stats['mappers'] += 1
        return True
    return False

def migrate_entity(file_path):
    """迁移 Entity 文件"""
    print(f"  📝 处理 Entity: {file_path.name}")
    
    content = read_file(file_path)
    if not content:
        return False
    
    # 检查是否已经迁移
    if 'com.baomidou' not in content:
        print(f"    ⏭️  已迁移,跳过")
        return True
    
    # 移除 MyBatis-Plus 导入
    content = re.sub(r'import com\.baomidou\.mybatisplus\.annotation\.\*\n?', '', content)
    content = re.sub(r'import com\.baomidou\.mybatisplus\.annotation\.\w+\n?', '', content)
    
    # 移除注解
    content = re.sub(r'@TableName\("[^"]+"\)\n?', '', content)
    content = re.sub(r'@TableId\(value = "id", type = IdType\.AUTO\)\n?', '', content)
    content = re.sub(r'@TableLogic\(value = "1", delval = "0"\)\n?', '', content)
    content = re.sub(r'@TableField\(fill = FieldFill\.INSERT\)\n?', '', content)
    content = re.sub(r'@TableField\(fill = FieldFill\.INSERT_UPDATE\)\n?', '', content)
    
    # 写入文件
    if write_file(file_path, content):
        print(f"    ✅ Entity 迁移完成")
        stats['entities'] += 1
        return True
    return False

def migrate_service_interface(file_path):
    """迁移 Service 接口文件"""
    print(f"  📝 处理 Service 接口: {file_path.name}")
    
    content = read_file(file_path)
    if not content:
        return False
    
    # 检查是否已经迁移
    if 'IService' not in content and 'com.baomidou' not in content:
        print(f"    ⏭️  已迁移,跳过")
        return True
    
    # 1. 替换 Page 导入
    content = content.replace(
        'import com.baomidou.mybatisplus.extension.plugins.pagination.Page',
        'import com.vipamp.vipclaw.common.page.Page'
    )
    
    # 2. 移除 IService 导入
    content = re.sub(r'import com\.baomidou\.mybatisplus\.extension\.service\.IService\n?', '', content)
    
    # 3. 移除 IService 继承
    content = re.sub(r'\s*:\s*IService<\w+>', '', content)
    
    # 写入文件
    if write_file(file_path, content):
        print(f"    ✅ Service 接口迁移完成")
        stats['services'] += 1
        return True
    return False

def migrate_service_impl(file_path):
    """迁移 ServiceImpl 文件"""
    print(f"  📝 处理 ServiceImpl: {file_path.name}")
    
    content = read_file(file_path)
    if not content:
        return False
    
    # 检查是否已经迁移
    if 'ServiceImpl<' not in content and 'ServiceImpl(' not in content:
        print(f"    ⏭️  已迁移,跳过")
        return True
    
    # 提取 Mapper 和 Entity 名称
    mapper_match = re.search(r'ServiceImpl<(\w+Mapper),\s*(\w+)>', content)
    if not mapper_match:
        print(f"    ⚠️  无法提取 Mapper/Entity 名")
        return False
    
    mapper_name = mapper_match.group(1)
    entity_name = mapper_match.group(2)
    
    # 1. 替换导入
    content = content.replace(
        'import com.baomidou.mybatisplus.extension.plugins.pagination.Page',
        'import com.github.pagehelper.PageHelper\nimport com.vipamp.vipclaw.common.page.Page'
    )
    content = content.replace(
        'import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl',
        'import java.time.LocalDateTime'
    )
    
    # 2. 移除 ServiceImpl 继承,添加 Mapper 注入
    def replace_service_impl(match):
        class_name = match.group(1)
        mapper_var = mapper_name[0].lower() + mapper_name[1:]
        return f'class {class_name}(\n    private val {mapper_var}: {mapper_name}\n)'
    
    content = re.sub(
        r'class\s+(\w+)\(\s*\n?\s*\)\s*:\s*ServiceImpl<\w+Mapper,\s*\w+>',
        replace_service_impl,
        content
    )
    
    # 3. 替换 baseMapper 为 mapper 实例
    mapper_var = mapper_name[0].lower() + mapper_name[1:]
    content = content.replace('baseMapper.', f'{mapper_var}.')
    
    # 4. 替换 getById, save, updateById, removeById
    content = content.replace(f'getById(', f'{mapper_var}.selectById(')
    content = re.sub(r'\bsave\((\w+)\)', f'{mapper_var}.insert(\\1) > 0', content)
    content = re.sub(r'\bupdateById\((\w+)\)', f'{mapper_var}.updateById(\\1) > 0', content)
    content = re.sub(r'removeById\((\w+)\)', f'{mapper_var}.deleteById(\\1) > 0', content)
    
    # 5. 替换分页逻辑为 PageHelper
    # 匹配手动分页代码块
    page_pattern = r'(val\s+all\w+\s*=\s*\w+\.select\w+\([^)]*\))\s*\n\s*//\s*手动分页\s*\n\s*val\s+page\s*=\s*Page<\w+>\([^)]*\)\s*\n\s*val\s+fromIndex\s*=\s*\([^)]*\)\s*\n\s*val\s+toIndex\s*=\s*min\([^)]*\)\s*\n\s*page\.records\s*=\s*if\s*\([^)]*\)\s*\{\s*\n\s*\w+\.subList\([^)]*\)\s*\n\s*\}\s*else\s*\{\s*\n\s*emptyList\(\)\s*\n\s*\}\s*\n\s*page\.total\s*=\s*\w+\.size\.toLong\(\)\s*\n\s*return\s+page'
    
    def replace_page(match):
        query_line = match.group(1)
        var_name = re.search(r'val\s+(all\w+)', query_line).group(1)
        return f'''// 使用 PageHelper 分页
        PageHelper.startPage<{entity_name}>(current, size)
        val {var_name.replace('all', '')} = {query_line.split('=', 1)[1].strip()}
        
        // 转换为 PageInfo
        val pageInfo = com.github.pagehelper.PageInfo({var_name.replace('all', '')})
        return Page.fromPageInfo(pageInfo)'''
    
    content = re.sub(page_pattern, replace_page, content, flags=re.DOTALL)
    
    # 6. 添加 createTime/updateTime 设置 (简化版)
    # 在 save 调用前添加时间设置
    content = re.sub(
        r'(\w+)\.createTime\s*=\s*LocalDateTime\.now\(\)\s*\n\s*\1\.updateTime\s*=\s*LocalDateTime\.now\(\)\s*\n\s*\w+Mapper\.insert',
        lambda m: f'{m.group(1)}.createTime = LocalDateTime.now()\n            {m.group(1)}.updateTime = LocalDateTime.now()\n            {mapper_name}.insert',
        content
    )
    
    # 写入文件
    if write_file(file_path, content):
        print(f"    ✅ ServiceImpl 迁移完成")
        stats['services'] += 1
        return True
    return False

def main():
    """主函数"""
    print("=" * 80)
    print("🚀 MyBatis-Plus 自动迁移工具")
    print("=" * 80)
    print()
    
    # 1. 迁移 Mapper
    print("📦 开始迁移 Mapper...")
    mapper_dir = ADMIN_DIR / "mapper"
    if mapper_dir.exists():
        for file in mapper_dir.glob("*Mapper.kt"):
            if file.name not in ['TokenStatsMapper.kt']:  # TokenStatsMapper 已迁移
                migrate_mapper(file)
    print()
    
    # 2. 迁移 Entity
    print("📦 开始迁移 Entity...")
    entity_dir = ADMIN_DIR / "entity"
    if entity_dir.exists():
        for file in entity_dir.glob("*.kt"):
            migrate_entity(file)
    print()
    
    # 2.5. 迁移 Service 接口
    print("📦 开始迁移 Service 接口...")
    service_interface_dir = ADMIN_DIR / "service"
    if service_interface_dir.exists():
        for file in service_interface_dir.glob("*.kt"):
            if file.name not in ['AuthzService.kt']:  # 跳过不相关的
                migrate_service_interface(file)
    print()
    
    # 3. 迁移 ServiceImpl
    print("📦 开始迁移 ServiceImpl...")
    service_dir = ADMIN_DIR / "service" / "impl"
    if service_dir.exists():
        for file in service_dir.glob("*ServiceImpl.kt"):
            if file.name not in ['AuthServiceImpl.kt', 'CaptchaServiceImpl.kt']:  # 这些不依赖 MyBatis-Plus
                migrate_service_impl(file)
    print()
    
    # 打印统计信息
    print("=" * 80)
    print("📊 迁移统计:")
    print(f"  ✅ Mapper: {stats['mappers']} 个")
    print(f"  ✅ Entity: {stats['entities']} 个")
    print(f"  ✅ ServiceImpl: {stats['services']} 个")
    if stats['errors']:
        print(f"  ❌ 错误: {len(stats['errors'])} 个")
        for error in stats['errors']:
            print(f"    - {error}")
    print("=" * 80)
    print()
    print("⚠️  重要提示:")
    print("  1. 请检查迁移后的代码,特别是 SQL 语句是否正确")
    print("  2. 运行 'mvn clean compile' 验证编译")
    print("  3. 运行测试确保功能正常")
    print("  4. IDE 可能显示编译错误,这是索引问题,实际编译没问题")
    print()

if __name__ == '__main__':
    main()
