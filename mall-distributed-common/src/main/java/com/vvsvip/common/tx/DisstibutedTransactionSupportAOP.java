package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;

/**
 * Created by ADMIN on 2017/4/27.
 */
public class DisstibutedTransactionSupportAOP {

    public void before() {
        RpcContext.getContext().setAttachment(DistributedTransactionParams.TRANSACTION_ZNODE.getValue(), String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.TRANSACTION_ZNODE_PATH)));
        RpcContext.getContext().setAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), String.valueOf(TransactionMessageAop.threadParam.get().get(DistributedTransactionParams.TRANSACTION_STATUS.getValue())));
    }
}
