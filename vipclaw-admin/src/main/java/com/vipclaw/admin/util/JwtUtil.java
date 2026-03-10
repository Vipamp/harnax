package com.vipclaw.admin.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:vipclaw-secret-key-2026}")
   private String secret;

    @Value("${jwt.expiration:7200000}")
   private Long expiration;

    /**
     * 获取签名密钥
     */
   private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
       return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT Token
     * 
     * @param userId 用户 ID
     * @param username 用户名
     * @return JWT Token
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expiration);
        
       return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 从 Token 中获取用户 ID
     * 
     * @param token JWT Token
     * @return 用户 ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
       return claims.get("userId", Long.class);
    }

    /**
     * 从 Token 中获取用户名
     * 
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
       return claims.getSubject();
    }

    /**
     * 验证 Token 是否有效
     * 
     * @param token JWT Token
     * @return true/false
     */
    public boolean validateToken(String token) {
       try {
            final Date expiration= getExpirationDateFromToken(token);
           return !expiration.before(new Date());
        } catch (Exception e) {
           log.error("验证 Token 失败：{}", e.getMessage());
           return false;
        }
    }

    /**
     * 获取过期时间
     * 
     * @param token JWT Token
     * @return 过期时间
     */
   private Date getExpirationDateFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
       return claims.getExpiration();
    }

    /**
     * 获取 Claims
     * 
     * @param token JWT Token
     * @return Claims
     */
   private Claims getClaimsFromToken(String token) {
       return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取过期时间（毫秒）
     * 
     * @return 过期时间
     */
    public Long getExpirationTime() {
       return expiration;
    }
}
