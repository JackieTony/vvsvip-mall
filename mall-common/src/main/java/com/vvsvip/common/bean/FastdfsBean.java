package com.vvsvip.common.bean;

import java.io.Serializable;

/**
 * Fastdfs上传反馈信息
 * Created by blues on 2017/4/23.
 */
public class FastdfsBean implements Serializable {
    /**
     * 组名
     */
    private String groupName;
    /**
     * 远程文件
     */
    private String remoteFile;


    public FastdfsBean() {
    }

    public FastdfsBean(String[] result) {
        this.groupName = result[0];
        this.remoteFile = result[1];
    }


    public FastdfsBean(String groupName, String remoteFile) {
        this.groupName = groupName;
        this.remoteFile = remoteFile;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getRemoteFile() {
        return remoteFile;
    }

    public void setRemoteFile(String remoteFile) {
        this.remoteFile = remoteFile;
    }

    @Override
    public String toString() {
        return "FastdfsBean{" +
                "groupName='" + groupName + '\'' +
                ", remoteFile='" + remoteFile + '\'' +
                '}';
    }
}
