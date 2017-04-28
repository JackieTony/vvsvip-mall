package com.vvsvip.common.util;

import com.vvsvip.common.bean.FastdfsBean;
import org.csource.common.FastDFSException;
import org.csource.common.NameValuePair;
import org.csource.fastdfs.*;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Fastdfs 分布式文件系统工具类
 * Created by blues on 2017/4/23.
 */
public class FastdfsUtil {

    static {
        init();
    }

    private static void init() {
        try {
            String clientConfigFile = "config/fastdfs-client.conf";
            URL url = FastdfsUtil.class.getClassLoader().getResource(clientConfigFile);
            ClientGlobal.init(url.getFile());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (FastDFSException e) {
            e.printStackTrace();
        }
    }

    private static StorageClient getStorageClient() throws IOException {
        TrackerClient tracker = new TrackerClient();
        TrackerServer trackerServer = tracker.getConnection();

        StorageServer storageServer = null;

        StorageClient storageClient = new StorageClient(trackerServer,
                storageServer);
        return storageClient;
    }

    /**
     * 上传文件到fastdfs服务器
     *
     * @param file    需要上传的文件
     * @param metaMap 附加信息，可以通过getFileMate获取这些附加信息
     * @return 返回上传的 groupId 和 remoteFileName 这两个信息非常重要，需要使用这两个值确定文件
     * @throws Exception
     */
    public static FastdfsBean upload(File file, Map<String, String> metaMap) throws Exception {
        return upload(file, null, metaMap);
    }

    /**
     * 上传文件到fastdfs服务器
     *
     * @param file      需要上传的文件
     * @param groupName 组名
     * @param metaMap   附加信息，可以通过getFileMate获取这些附加信息
     * @return 返回上传的 groupId 和 remoteFileName 这两个信息非常重要，需要使用这两个值确定文件
     * @throws Exception
     */
    public static FastdfsBean upload(File file, String groupName, Map<String, String> metaMap) throws Exception {
        NameValuePair[] nvps = null;
        if (metaMap != null) {
            nvps = new NameValuePair[metaMap.size()];
            int off = 0;
            for (Map.Entry<String, String> entry : metaMap.entrySet()) {
                nvps[off++] = new NameValuePair(entry.getKey(), entry.getValue());
            }
        }
        String fileIds[] = getStorageClient().upload_file(groupName, FileUtil.file2Byte(file), FileUtil.getSuffixName(file), nvps);
        System.out.println("组名：" + fileIds[0]);
        System.out.println("路径: " + fileIds[1]);
        return new FastdfsBean(fileIds);
    }

    @Test
    public void test() {
        URL url = getClass().getClassLoader().getResource("logback.xml");
        File file = new File(url.getFile());
        try {
            upload(file, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testDelete() throws Exception {
        boolean isSuccess = delete("group1", "M00/00/00/wKgAZlj6tcKAf2eFAAAFuM3mKwY10.conf");
        System.out.println(isSuccess);
        System.out.println(isSuccess ? "删除成功" : "删除失败");
    }

    /**
     * 下载数据
     *
     * @param groupName      fastdfs上传时使用分组Id 默认为group1
     * @param remoteFileName fastdfs上传时返回的文件 地址+文件名
     * @return 返回从服务器下载的数据
     * @throws Exception
     */
    public static byte[] download(String groupName, String remoteFileName) throws Exception {
        if (groupName == null) {
            groupName = "group1";
        }
        byte[] data = getStorageClient().download_file(groupName, remoteFileName);
        return data;
    }


    /**
     * 获取文件相关信息 比如上传到服务器的时间
     *
     * @param groupName      fastdfs上传时使用分组Id 默认为group1
     * @param remoteFileName fastdfs上传时返回的文件 地址+文件名
     * @return 文件信息Bean FileInfo
     * @throws Exception
     */
    public static FileInfo getFileInfo(String groupName, String remoteFileName) throws Exception {
        StorageClient storageClient = getStorageClient();
        FileInfo fileInfo = storageClient.get_file_info(groupName, remoteFileName);
        return fileInfo;
    }

    /**
     * @param groupName
     * @param remoteFileName
     * @return
     * @throws Exception
     */
    public Map<String, String> getFileMate(String groupName, String remoteFileName) throws Exception {
        NameValuePair nvps[] = getStorageClient().get_metadata(groupName, remoteFileName);
        if (nvps != null) {
            Map<String, String> metaMap = new HashMap<String, String>();
            for (NameValuePair nvp : nvps) {
                metaMap.put(nvp.getName(), nvp.getValue());
            }
            return metaMap;
        }
        return null;
    }

    /**
     * @param groupName      fastdfs上传时使用分组Id 默认为group1
     * @param remoteFileName fastdfs上传时返回的文件 地址+文件名
     * @return 返回是否删除成功
     * @throws Exception
     */
    public static boolean delete(String groupName, String remoteFileName) throws Exception {
        int isSuccess = getStorageClient().delete_file(groupName, remoteFileName);
        return isSuccess == 0;
    }
}
