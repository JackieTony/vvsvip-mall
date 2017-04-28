package com.vvsvip.common.tx;

/**
 * Created by ADMIN on 2017/4/24.
 */
public enum DistributedTransactionParams {

    YES("YES"), // 开启事务
    NO("NO"),   // 无事务
    COMMITED("COMMITED"),   // 已提交
    ROLL_BACK("rollback"),  // 已回滚
    TRANSACTION_STATUS("TRANSACTION_STATUS"),   // 当前事务状态
    TRANSACTION_ZNODE("TRANSACTION_ZNODE_KEY"), // 当前ZNODE主键
    ZK_PATH("/transaction"),    // 事务节点根目录
    ZK_LOCK("/locks"),          // 分布式锁
    INTERVAL("&"),// 参数拼接间隔
    NEW("NEW");

    private String value;

    DistributedTransactionParams(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
