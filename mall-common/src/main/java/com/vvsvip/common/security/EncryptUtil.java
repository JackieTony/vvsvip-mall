package com.vvsvip.common.security;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import java.io.IOException;

/**
 * 常用密码工具类
 * Created by ADMIN on 2017/4/25.
 */
public class EncryptUtil {

    private static BASE64Encoder base64Encoder = new BASE64Encoder();
    private static BASE64Decoder base64Decoder = new BASE64Decoder();

    /**
     * Base64转码
     *
     * @param bytes 源数据
     * @return 返回转码后的字符串
     */
    public static String encodeBase64(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        return base64Encoder.encode(bytes);
    }

    /**
     * Base64解码
     *
     * @param cipher 密文
     * @return 返回解密后byte数据
     * @throws IOException 转码异常
     */
    public static byte[] decodeBase64(String cipher) throws IOException {
        if (cipher == null) {
            cipher = "";
        }
        return base64Decoder.decodeBuffer(cipher);
    }
}
