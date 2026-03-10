package com.vipclaw.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vipclaw.admin.entity.SysTokenBlacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Token 黑名单 Mapper
 */
@Mapper
public interface SysTokenBlacklistMapper extends BaseMapper<SysTokenBlacklist> {

    /**
     * 根据 Token 哈希值查询
     * 
     * @param tokenHash Token 哈希值
     * @return Token 黑名单记录
     */
    @Select("SELECT * FROM sys_token_blacklist WHERE token_hash = #{tokenHash} AND expire_time > NOW()")
   SysTokenBlacklist selectByTokenHash(@Param("tokenHash") String tokenHash);
}
