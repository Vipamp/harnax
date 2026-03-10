package com.vipclaw.admin.service.impl;

import com.vipclaw.admin.dto.CaptchaResponse;
import com.vipclaw.admin.exception.BizException;
import com.vipclaw.admin.service.CaptchaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务实现类
 */
@Slf4j
@Service
public class CaptchaServiceImpl implements CaptchaService {

    // 简化实现：使用内存存储验证码（实际项目中应使用 Redis）
    private final ConcurrentHashMap<String, CaptchaInfo> captchaStore = new ConcurrentHashMap<>();

    // 验证码宽度
    private static final int WIDTH = 120;
    // 验证码高度
    private static final int HEIGHT = 40;
    // 验证码字符数
    private static final int CODE_LENGTH = 4;
    // 验证码过期时间（5 分钟）
    private static final long EXPIRE_TIME = 300;

    // 验证码字符集合（去除容易混淆的字符）
    private static final String CHAR_SET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @Override
    public CaptchaResponse generateCaptcha() {
        try {
            // 1. 生成随机验证码
            String code = generateRandomCode();

            // 2. 生成验证码图片
            BufferedImage image = createCaptchaImage(code);

            // 3. 转换为 Base64
            String base64Image = convertToBase64(image);

            // 4. 生成唯一 key
            String captchaKey = generateCaptchaKey();

            // 5. 存储验证码信息
            captchaStore.put(captchaKey, new CaptchaInfo(code, System.currentTimeMillis()));

            log.info("生成验证码，captchaKey: {}", captchaKey);

            // 6. 返回响应
            return CaptchaResponse.builder()
                    .imageBase64(base64Image)
                    .captchaKey(captchaKey)
                    .expiresIn(EXPIRE_TIME)
                    .build();
        } catch (Exception e) {
            log.error("生成验证码失败", e);
            throw new BizException("生成验证码失败：" + e.getMessage());
        }
    }

    @Override
    public boolean validateCaptcha(String captchaKey, String code) {
        if (captchaKey == null || code == null) {
            return false;
        }

        CaptchaInfo captchaInfo = captchaStore.get(captchaKey);
        if (captchaInfo == null) {
            log.warn("验证码不存在，captchaKey: {}", captchaKey);
            return false;
        }

        // 检查是否过期
        long currentTime = System.currentTimeMillis();
        if (currentTime - captchaInfo.createTime > TimeUnit.SECONDS.toMillis(EXPIRE_TIME)) {
            captchaStore.remove(captchaKey);
            log.warn("验证码已过期，captchaKey: {}", captchaKey);
            return false;
        }

        // 验证验证码（忽略大小写）
        boolean valid = captchaInfo.code.equalsIgnoreCase(code);

        // 验证后删除验证码（一次性使用）
        captchaStore.remove(captchaKey);

        log.info("验证码验证{}，captchaKey: {}, code: {}",
                valid ? "成功" : "失败", captchaKey, code);

        return valid;
    }

    /**
     * 生成随机验证码
     */
    private String generateRandomCode() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = random.nextInt(CHAR_SET.length());
            sb.append(CHAR_SET.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 创建验证码图片
     */
    private BufferedImage createCaptchaImage(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 设置背景色
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 设置字体
        g.setFont(new Font("Arial", Font.BOLD, 24));

        // 绘制干扰线
        drawDisturbanceLines(g);

        // 绘制验证码文字
        drawCodeString(g, code);

        // 绘制干扰点
        drawDisturbancePoints(g);

        g.dispose();
        return image;
    }

    /**
     * 绘制干扰线
     */
    private void drawDisturbanceLines(Graphics2D g) {
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            g.setColor(getRandomColor());
            int x1 = random.nextInt(WIDTH);
            int y1 = random.nextInt(HEIGHT);
            int x2 = random.nextInt(WIDTH);
            int y2 = random.nextInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    /**
     * 绘制验证码文字
     */
    private void drawCodeString(Graphics2D g, String code) {
        Random random = new Random();
        int fontSize = HEIGHT - 8;
        int charWidth = WIDTH / CODE_LENGTH;

        for (int i = 0; i < code.length(); i++) {
            g.setColor(getRandomColor());
            g.drawString(String.valueOf(code.charAt(i)),
                    i * charWidth + 10,
                    fontSize + random.nextInt(5) + 5);
        }
    }

    /**
     * 绘制干扰点
     */
    private void drawDisturbancePoints(Graphics2D g) {
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            g.setColor(getRandomColor());
            int x = random.nextInt(WIDTH);
            int y = random.nextInt(HEIGHT);
            g.drawRect(x, y, 1, 1);
        }
    }

    /**
     * 获取随机颜色
     */
    private Color getRandomColor() {
        Random random = new Random();
        return new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200));
    }

    /**
     * 将图片转换为 Base64
     */
    private String convertToBase64(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        return "data:image/png;base64," + base64;
    }

    /**
     * 生成验证码 key
     */
    private String generateCaptchaKey() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 验证码信息内部类
     */
    private static class CaptchaInfo {
        String code;
        long createTime;

        CaptchaInfo(String code, long createTime) {
            this.code = code;
            this.createTime = createTime;
        }
    }
}
