---
name: create-entity-prd
description: 用户按照的需求和规范，设计一个操作实体的 PRD 文档。
---

# DTO-PRD-Creater

按照用户实体描述或者 schema 定义，编写完整的 DTO 类，以及CURD 操作逻辑，生成最终完整的 PRD 文档。

# 工作流程

## 一、数据实体设计

### 1.1 Step1：实体字段分析

判断用户是否给了 schema 文档，如果没有具体的 schema 文档，需要自己生成 schema 信息

### 1.2 Step2：规范检查

检查 schema 文档是否符合如下的 schema 规范

```text
XXX
```

### 1.3 Step3：补充字段描述

- 是否可空
- 是否唯一
- 默认值
- 字段有效值验证逻辑

### 1.4 Step：生成建表语句

按照上述的 schema 信息，生成 mysql 表的建表语句，并按照需要配上对应的主键约束，非空约束，唯一约束等。

## 二、生成实体和 DTO 对象

### 2.1 创建数据库实体

### 2.2 创建实体的请求 DTO (XXXCreateRequest)

- 要点：
    - 实体的    `ID`,`status`, `active`, `createTime`, `updateTime` 在创建实体时不需要加上，后台自己生成
    - 补充其他字段的字段类型，是否可空，校验规则描述
    - 补充待更新字段的更新规则
- 示例：

### 2.3 更新实体的请求 DTO (XXXUpdateRequest)

- 要点：
    - `status`,`active`,`createTime`,`updateTime` 这四个字段不包含
    - 补充其他字段的字段类型，是否可空，校验规则描述
    - 补充待更新字段的更新规则
- 示例：

### 2.4 请求实体的 DTO (XXResponse)

- 要点：
    - 密码、`apiKey` 等重要信息不对外暴露。
    - 其他字段均包含

## 三、CURD接口操作

### 3.1 分页查询实体

- 接口请求
    - **接口方式**: `GET`
    - **接口URL**: `/list`
    - **Content-Type**: `application/json`
- 请求参数：
    - pageNum：当前分页页码，默认为1
    - pageSize：每页实体数量，默认为10
    - name：名称(模糊查询)，可null，为 null 时 name 不做为筛选条件
    - status：状态筛选项，可null，为 null 时 status 不做为筛选条件
- 业务逻辑：
    - step1：构建查询条件
    - step2：执行分页查询
    - step3：将分页查询的数据库实体转换成 `XXXResponse` 实体
    - step4：将转换后的分页查询结果包装成 `ResultVo` 对象返回

### 3.2 新建实体

- 接口请求
    - **接口方式**: `POST`
    - **接口URL**: `/`
    - **Content-Type**:`application/json`
- 请求示例：json 格式的 `XXXCreateRequest` 实体和示例值
- 业务逻辑：
    - step1：参数非空、空字符串、非法格式之类的限制，都需要校验
    - step2：名称是否已存在，关联项是否存在等校验。
    - step3：密码、apikey 等敏感信息需要加密处理
    - step4：构建实体对象，保存实体
    - step5：返回结果  `ResultVo.success()` 或者  `ResultVo.error()`。

### 3.3 更新实体

- 接口请求
    - **接口方式**: `PUT`
    - **接口URL**: `/update/{id}`
    - **Content-Type**:`application/json`
- 请求示例：json 格式的 `XXXUpdateRequest` 实体和示例值
- 业务逻辑：
    - step1：检查该 ID 对应的实体是否存在（不存在就抛异常），填了值的字段是否合法，比如参数非空、空字符串、非法格式之类的限制，都需要校验
    - step2：检查更新后的字段值是否违反唯一约束、是否存在关联项。
    - step3：密码、apikey 等敏感信息需要加密处理
    - step4：按照有值的字段更新实体。
    - step5：返回结果  `ResultVo.success()` 或者  `ResultVo.error()`。

### 3.4 修改实体状态

- 接口请求
    - **接口方式**: `PUT`
    - **接口URL**: `/toggle/{id}`
    - **Content-Type**:`application/json`
- 请求参数：
    - status：0 或者 1
- 业务逻辑：
    - step1：检查该 ID 对应的实体是否存在（不存在就抛异常）
    - step2：更新该 ID 所对应的实体的 `status` 字段。
    - step3：返回结果  `ResultVo.success()` 或者  `ResultVo.error()`。

### 3.5 删除实体

- 接口请求
    - **接口方式**: `DELETE`
    - **接口URL**: `/{id}`
    - **Content-Type**:`application/json`
- 业务逻辑：
    - step1：检查该 ID 对应的实体是否存在（不存在就抛异常）
    - step2：通过 ID 检查是否有其他实体关联该实体，如果有关联，提示不允许删除。
    - step3：修改实体的 `active` 为 `0` 逻辑删除，不允许物理删除。
    - step4：返回结果  `ResultVo.success()` 或者  `ResultVo.error()`。
