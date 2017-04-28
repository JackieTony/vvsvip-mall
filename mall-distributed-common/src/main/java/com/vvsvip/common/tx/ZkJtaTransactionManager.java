package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Created by ADMIN on 2017/4/24.
 */
public class ZkJtaTransactionManager extends JtaTransactionManager {
    Logger logger = LoggerFactory.getLogger(ZkJtaTransactionManager.class);

    /**
     * 事务开始
     *
     * @param transaction
     * @param definition
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        logger.debug(RpcContext.getContext().getLocalHost() + "开启事务");
        DistributedTransactionSupport.doBegin();
    }

    /**
     * 事务回滚
     *
     * @param status
     */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        super.doRollback(status);
        logger.debug(RpcContext.getContext().getLocalHost() + "回滚事务");
        TransactionMessageAop.paramsThreadLocal.set(DistributedTransactionParams.ROLL_BACK);
        DistributedTransactionSupport.doRollback();
    }

    /**
     * 事务提交
     *
     * @param status
     */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        super.doCommit(status);
        logger.info(RpcContext.getContext().getLocalHost() + "事务提交");
        TransactionMessageAop.paramsThreadLocal.set(DistributedTransactionParams.COMMITED);
    }
}
