package com.studyagent.common.verla.util;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 简易 IPv4 CIDR 匹配：用于 /internal 鉴权 IP 白名单
 * <p>
 * 对应文档 §23.1 L1
 */
@UtilityClass
public class VerlaCidrMatcher {

    /**
     * 判断 ip 是否落在任一 CIDR 内（支持 IPv4 + IPv6 全 0 配置）
     *
     * @param ip       客户端 IPv4 地址
     * @param allowed  CIDR 列表，例如 ["10.20.0.0/16", "127.0.0.1/32"]
     * @return true = 命中任一 CIDR
     */
    public static boolean match(String ip, List<String> allowed) {
        if (StringUtils.isBlank(ip) || allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (String cidr : allowed) {
            if (singleMatch(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean singleMatch(String ip, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            String network;
            int prefix;
            if (slash < 0) {
                network = cidr;
                prefix = 32;
            } else {
                network = cidr.substring(0, slash);
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            }
            long ipLong = ipv4ToLong(ip);
            long netLong = ipv4ToLong(network);
            long mask = prefix == 0 ? 0L : (-1L << (32 - prefix)) & 0xFFFFFFFFL;
            return (ipLong & mask) == (netLong & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not ipv4: " + ip);
        }
        long v = 0;
        for (String p : parts) {
            int n = Integer.parseInt(p);
            if (n < 0 || n > 255) {
                throw new IllegalArgumentException("bad octet: " + p);
            }
            v = (v << 8) | n;
        }
        return v;
    }
}
