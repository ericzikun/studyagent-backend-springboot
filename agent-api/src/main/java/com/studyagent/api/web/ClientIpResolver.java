package com.studyagent.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 解析反向代理后的客户端地址，仅供短期匿名写入限流使用。
 *
 * <p>仅当 socket 对端是本机或私网代理时才信任转发头，避免直接访问后端端口的客户端伪造 IP。
 * 解析结果不会进入数据库。</p>
 */
@Component
public class ClientIpResolver {

    /**
     * 优先取 Nginx 覆盖写入的 X-Real-IP，再回退到 X-Forwarded-For 和 socket 地址。
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!isTrustedProxyAddress(remoteAddress)) {
            return remoteAddress;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        return remoteAddress;
    }

    private boolean isTrustedProxyAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        if (address.equals("::1") || address.startsWith("127.")
                || address.startsWith("10.") || address.startsWith("192.168.")) {
            return true;
        }
        if (!address.startsWith("172.")) {
            return false;
        }
        String[] parts = address.split("\\.", 3);
        if (parts.length < 2) {
            return false;
        }
        try {
            int secondOctet = Integer.parseInt(parts[1]);
            return secondOctet >= 16 && secondOctet <= 31;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
