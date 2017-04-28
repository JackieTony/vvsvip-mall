package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;

/**
 * 分布式事务标记
 * Created by ADMIN on 2017/4/24.
 */
public class DistributedTransactionSupport {

    public static void doBegin() {
        // 是否为消费者
        if (isConsumerSide()) {
            RpcContext.getContext().setAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), DistributedTransactionParams.YES.getValue());
            TransactionMessageAop.threadParam.get().put(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), DistributedTransactionParams.YES.getValue());
        }
    }

    public static void doCommited() {
        if (isConsumerSide()) {
            RpcContext.getContext().setAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), null);
        }
    }

    public static void doRollback() {
        if (isConsumerSide()) {
            RpcContext.getContext().setAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), null);
        }
    }

    public static boolean isDistributedTransaction() {
        Boolean isDistributed = (Boolean) TransactionMessageAop.threadParam.get().get(TransactionMessageAop.IS_DISTRIBUTED);
        if (isDistributed == null) {
            isDistributed = false;
        }
        return DistributedTransactionParams.YES.getValue().equals(RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue()))
                || isDistributed;
    }

    public static boolean isExecutable() {
        String execStr = String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.EXECUTE_SIGN));
        if (execStr == null) {
            return false;
        }
        Boolean exec = Boolean.parseBoolean(execStr);
        return exec;
    }

    public static boolean isConsumerSide() {
        Boolean isConsumerSide = (Boolean) TransactionMessageAop.threadParam.get().get(TransactionMessageAop.IS_CONSUMER_SIDE);
        if (isConsumerSide == null) {
            if (RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue()) == null) {
                return true;
            }
            return false;
        }
        return isConsumerSide;
    }

    public static String getZNode() {
        return String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.TRANSACTION_ZNODE_PATH));
    }
}
