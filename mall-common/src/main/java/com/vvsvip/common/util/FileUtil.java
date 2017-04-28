package com.vvsvip.common.util;

import java.io.*;

/**
 * 文件工具类
 * Created by blues on 2017/4/23.
 */
public class FileUtil {

    public static byte[] file2Byte(File source) throws IOException {
        InputStream inputStream = new FileInputStream(source);
        byte[] data = new byte[inputStream.available()];
        inputStream.read(data);
        return data;
    }

    public static InputStream byte2InputStream(byte[] data) {
        InputStream is = new ByteArrayInputStream(data);
        return is;
    }

    public static File byte2File(byte[] data, File out) throws IOException {
        OutputStream output = new FileOutputStream(out);
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(output);
        try {
            bufferedOutput.write(data);
            bufferedOutput.flush();
            return out;
        } finally {
            bufferedOutput.flush();
            bufferedOutput.close();
        }
    }

    public static String getSuffixName(File file) {
        String fileName = file.getName();
        int suffixIndex = fileName.lastIndexOf(".");
        String suffixName = null;
        if (suffixIndex != -1 && suffixIndex < fileName.length() - 1) {
            suffixName = fileName.substring(suffixIndex + 1);
        }
        return suffixName;
    }
}
