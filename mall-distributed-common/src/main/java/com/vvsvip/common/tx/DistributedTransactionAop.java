package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import com.atomikos.icatch.jta.UserTransactionManager;
import com.vvsvip.common.model.TransactionMessage;
import com.vvsvip.common.dao.TransactionMessageMapper;
import org.I0Itec.zkclient.ZkClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.transaction.*;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;

/**
 * Created by ADMIN on 2017/4/24.
 */
public class DistributedTransactionAop {

    private static final Logger logger = LoggerFactory.getLogger(DistributedTransactionAop.class);
    @Autowired
    private UserTransactionManager atomikosTransactionManager;
    @Autowired
    private TransactionMessageMapper transactionMessageMapper;

    @Autowired
    private ZkClient zkClient;

    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        begin();
        Object obj = joinPoint.proceed();
        end();
        return obj;
    }

    public void throwing() throws SystemException {
        logger.info("进入throwing");
        if (DistributedTransactionSupport.isExecutable()
                && !DistributedTransactionSupport.isConsumerSide() && DistributedTransactionSupport.getZNode() != null) {
            new TransactionThread(atomikosTransactionManager.getTransaction(),
                    TransactionMessageAop.threadParam.get(), DistributedTransactionParams.ROLL_BACK, zkClient, transactionMessageMapper).start();
        } else {
            atomikosTransactionManager.rollback();
            logger.debug(RpcContext.getContext().getLocalHost() + "回滚事务");
            TransactionMessageAop.paramsThreadLocal.set(DistributedTransactionParams.ROLL_BACK);
            DistributedTransactionSupport.doRollback();
        }
    }

    public void begin() throws SystemException, NotSupportedException {
        atomikosTransactionManager.begin();
        logger.debug(RpcContext.getContext().getLocalHost() + "开启事务");
        DistributedTransactionSupport.doBegin();
    }

    public void end() throws HeuristicRollbackException, RollbackException, HeuristicMixedException, SystemException {
        logger.info("进入END");
        if (DistributedTransactionSupport.isExecutable()
                && !DistributedTransactionSupport.isConsumerSide() && DistributedTransactionSupport.getZNode() != null) {
            new TransactionThread(atomikosTransactionManager.getTransaction(),
                    TransactionMessageAop.threadParam.get(), DistributedTransactionParams.COMMITED, zkClient, transactionMessageMapper).start();
        } else {
            atomikosTransactionManager.commit();
            logger.debug(RpcContext.getContext().getLocalHost() + "提交事务");
            TransactionMessageAop.paramsThreadLocal.set(DistributedTransactionParams.ROLL_BACK);
            DistributedTransactionSupport.doRollback();
        }
    }

    private void complate(ZkClient zkClient, String providerSideNode) {
        zkClient.writeData(providerSideNode, "2", -1);
        zkClient.close();
    }

    class TransactionThread extends Thread {
        private Transaction transaction;
        private Hashtable<String, Object> threadParam;
        private DistributedTransactionParams param;
        private ZkClient zkClient;
        private TransactionMessageMapper transactionMessageMapper;

        public TransactionThread(Transaction transaction,
                                 Hashtable<String, Object> threadParam,
                                 DistributedTransactionParams param, ZkClient zkClient, TransactionMessageMapper transactionMessageMapper) {
            this.transaction = transaction;
            this.threadParam = threadParam;
            this.param = param;
            this.zkClient = zkClient;
            this.transactionMessageMapper = transactionMessageMapper;
        }

        @Override
        public void run() {
            this.setName("TransactionThread");
            String providerSideNode = String.valueOf(threadParam.get(TransactionMessageAop.CURRENT_ZNODE));
            if (DistributedTransactionParams.ROLL_BACK.getValue().equals(param.getValue())) {
                if (zkClient.exists(providerSideNode)) {
                    zkClient.writeData(providerSideNode, TransactionMessageAop.ROLLBACK_STATUS, -1);
                }
            } else if (DistributedTransactionParams.COMMITED.getValue().equals(param.getValue())) {
                zkClient.writeData(providerSideNode, TransactionMessageAop.COMMIT_STATUS, -1);
            }
            CountDownLatch countDownLatch = (CountDownLatch) threadParam.get(TransactionMessageAop.COUNT_DOWN_LATCH);
            try {
                logger.info("读锁打开");
                if (countDownLatch.getCount() > 0) {
                    countDownLatch.await(DistributedTransactionProviderSideAOP.listenerTimeout, DistributedTransactionProviderSideAOP.timeUnit);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                logger.info("读锁关闭");
            }

            String transactionPath = String.valueOf(threadParam.get(TransactionMessageAop.TRANSACTION_ZNODE_PATH));
            logger.error("===============================" + transactionPath + "============================");
            String data = zkClient.readData(transactionPath);
            if (data == null || TransactionMessageAop.ROLLBACK_STATUS.equals(data)) {
                try {
                    logger.debug("事务回滚");
                    transaction.rollback();
                    throw new RuntimeException("事务回滚");
                } catch (SystemException e) {
                    e.printStackTrace();
                } finally {
                    TransactionMessage transactionMessage = transactionMessageMapper.selectByUUID(String.valueOf(threadParam.get(TransactionMessageAop.UUID_KEY)));
                    transactionMessage.setStatus(TransactionMessageAop.ROLLBACK_STATUS);
                    transactionMessageMapper.updateByPrimaryKey(transactionMessage);

                    complate(zkClient, providerSideNode);
                }
            }
            if (DistributedTransactionParams.ROLL_BACK.getValue().equals(param.getValue())) {
                try {
                    transaction.rollback();
                } catch (SystemException e) {
                    e.printStackTrace();
                } finally {
                    TransactionMessage transactionMessage = transactionMessageMapper.selectByUUID(String.valueOf(threadParam.get(TransactionMessageAop.UUID_KEY)));
                    transactionMessage.setStatus(TransactionMessageAop.ROLLBACK_STATUS);
                    transactionMessageMapper.updateByPrimaryKey(transactionMessage);
                    logger.debug("事务回滚");

                    complate(zkClient, providerSideNode);
                }
            } else if (DistributedTransactionParams.COMMITED.getValue().equals(param.getValue())) {
                try {
                    transaction.commit();
                    TransactionMessage transactionMessage = transactionMessageMapper.selectByUUID(String.valueOf(threadParam.get(TransactionMessageAop.UUID_KEY)));
                    transactionMessage.setStatus(TransactionMessageAop.COMMIT_STATUS);
                    transactionMessageMapper.updateByPrimaryKey(transactionMessage);
                    logger.debug("事务提交");
                } catch (HeuristicMixedException | HeuristicRollbackException | RollbackException | SystemException e) {
                    logger.error("try {} commit", threadParam.get(TransactionMessageAop.UUID_KEY));
                } finally {
                    complate(zkClient, providerSideNode);
                }
            }
        }
    }
}
