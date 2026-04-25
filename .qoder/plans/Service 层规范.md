规范如下：
1. 分页查询：page，返回 Page<实体>
2. 按照 id 获取实体详细信息，方法名：`get<实体>`，返回可空的实体类
3. 创建实体：方法名：`create<实体>`，参数：实体的Request 类，返回Boolean
4. 更新实体：方法名：`update<实体>`，参数：id 和 实体的 Update 类，返回Boolean
5. 切换实体状态：方法名：`toggle<实体>`，参数：id 和 实体的 Update 类，返回Boolean
6. 删除实体：方法名：`delete<实体>`，参数：id ，返回Boolean
7. 实体转换Response 对象：方法名：`convertToResponse`，参数：实体，返回实体的 Response 类


mapper 层规范
1. 所有查询都需要加上 active = 1，
2. delete 方法名：`delete<实体>`，参数：id，返回 Boolean，都是逻辑查询
3. 所有实体的 status 字段，都不在 update 实体时更新
4. status 字段更新，需要通过 updateStatus 方法来进行。

单元测试规范
1. Service 层都通过 Mock Mapper 层来测试
2. Mapper 层都通过 testContainer 来测试
3. create 实体、update 实体时，所有字段都需要进行检测验证，确保所有字段赋值准确
4. 