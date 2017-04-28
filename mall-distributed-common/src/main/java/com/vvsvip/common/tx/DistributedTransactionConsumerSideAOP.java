package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import com.vvsvip.common.security.EncryptUtil;
import com.vvsvip.common.tx.annotation.DistributedTransaction;
import org.I0Itec.zkclient.ZkClient;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.transaction.TransactionManager;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by ADMIN on 2017/4/27.
 */
public class DistributedTransactionConsumerSideAOP {
    Logger logger = LoggerFactory.getLogger(DistributedTransactionConsumerSideAOP.class);

    /**
     * zookeeper namespace间隔
     */
    private static final String INTERVAL = DistributedTransactionParams.INTERVAL.getValue();
    /**
     * 当前节点 全路径
     */
    private String znode;
    @Autowired
    private ZkClient zkClient;

    static final long listenerTimeout = 300000;

    /**
     * 异常事务处理
     */
    @AfterThrowing("transactionMethod()")
    public void doAfterThrow() {
        logger.info("异常拦截开始回滚事务");
        String transactionPath = znode;
        if (transactionPath != null) {
            boolean stat = zkClient.exists(transactionPath);
            if (stat) {
                try {
                    logger.error(TransactionMessageAop.ROLLBACK_STATUS + "=======================================" + znode);
                    zkClient.writeData(transactionPath, TransactionMessageAop.ROLLBACK_STATUS, -1);
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    throw new RuntimeException("Transaction Failed,rollback..." + transactionPath);
                } finally {
                    complete(transactionPath, (Integer) TransactionMessageAop.threadParam.get().get(TransactionMessageAop.TRANSACTION_COUNT), true);
                    zkClient.deleteRecursive(transactionPath);
                    zkClient.close();
                }
            }
        }

    }

    /**
     * aop before 切入
     *
     * @param joinPoint AOP切面参数
     * @throws Throwable 参数持久化异常
     */
    public Object around(final ProceedingJoinPoint joinPoint) throws Throwable {
        //初始化上下文
        TransactionMessageAop.initContext(joinPoint);

        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.IS_CONSUMER_SIDE, true);
        Boolean exec = Boolean.parseBoolean(String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.EXECUTE_SIGN)));

        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method targetMethod = methodSignature.getMethod();
        DistributedTransaction distributedTransaction = targetMethod.getAnnotation(DistributedTransaction.class);

        CountDownLatch countDownLatch = null;
        if (distributedTransaction != null) {
            int transactionCount = 0;
            if (exec) {
                // 消费者节点 准备开启事务
                transactionCount = distributedTransaction.value();

                TransactionMessageAop.threadParam.get().put(TransactionMessageAop.TRANSACTION_COUNT, transactionCount);
                TransactionMessageAop.threadParam.get().put(DistributedTransactionParams.TRANSACTION_STATUS.getValue(), DistributedTransactionParams.YES.getValue());

                if (transactionCount > 0) {
                    logger.debug("ConsumerSide doAround begin");
                    beforeConsumerSide(joinPoint);
                    logger.debug("ConsumerSide doAround end");

                }
            }
            // 事务尾声处理
            if (distributedTransaction != null && transactionCount > 0) {
                countDownLatch = new CountDownLatch(1);
                new ConsumerSideThread(znode, joinPoint, transactionCount, countDownLatch).start();
            }
        }
        Object object = joinPoint.proceed();
        if (countDownLatch != null) {
            countDownLatch.await(listenerTimeout, TimeUnit.MILLISECONDS);
        }
        return object;
    }

    /**
     * 消费者事务准备
     *
     * @param joinPoint 切面参数集合
     * @throws Throwable 参数持久化异常
     */
    private void beforeConsumerSide(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean stat = zkClient.exists(DistributedTransactionParams.ZK_PATH.getValue());
        if (!stat) {
            try {
                zkClient.create(DistributedTransactionParams.ZK_PATH.getValue(), "", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            } catch (Throwable e) {

            }
        }
        // zkNamespace
        StringBuffer namespaceBuffer = new StringBuffer();

        // 获取本地IP地址
        String ip = InetAddress.getLocalHost().getHostAddress();
        // 获取当前方法所在的类
        Object target = joinPoint.getTarget();
        String clazzName = target.getClass().getName();

        // 获取当前方法的名字
        String methodName = joinPoint.getSignature().getName();
        // 获取当前方法的所有参数
        Object[] args = joinPoint.getArgs();

        byte[] params = null;
        if (args != null && args.length > 0) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(args);
            // 将所持有参数转为二进制数据
            params = byteArrayOutputStream.toByteArray();
        }
        // 拼接transaction znode namespace

        namespaceBuffer.append(ip)
                .append(INTERVAL).append(System.currentTimeMillis() + new SecureRandom().nextInt(100000) + "")
                .append(INTERVAL).append(clazzName)
                .append(INTERVAL).append(methodName)
                .append(INTERVAL).append(EncryptUtil.encodeBase64(params));

        znode = DistributedTransactionParams.ZK_PATH + "/" + URLEncoder.encode(namespaceBuffer.toString(), "UTF-8");


        RpcContext.getContext()
                .setAttachment(
                        DistributedTransactionParams.TRANSACTION_ZNODE.getValue()
                        , znode);
        // 创建事务节点
        zkClient.create(znode, "0", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.TRANSACTION_ZNODE_PATH, znode);
    }

    /**
     * 消费者事务监听
     * <p>
     *
     * @param currZnode        当前节点
     * @param joinPoint        切面参数集合
     * @param transactionCount 事务总数
     */
    private void consumerSideTransactionListener(String currZnode, ProceedingJoinPoint joinPoint, int transactionCount) {
        String transactionPath = currZnode;
        try {
            boolean isSuccess = isSuccess(transactionPath, transactionCount);
            if (isSuccess) {
                logger.debug("Transaction Success=======================================" + znode);
                zkClient.writeData(transactionPath, "1", -1);
            } else {
                logger.debug("Transaction Failed=======================================" + znode);
                zkClient.writeData(transactionPath, "-1", -1);
                throw new RuntimeException("Transaction Failed,Rollback " + transactionPath);
            }
        } finally {
            complete(transactionPath, transactionCount, false);
            zkClient.deleteRecursive(transactionPath);
            zkClient.close();
        }
    }

    private boolean isSuccess(String transactionPath, Integer transactionCount) {
        boolean isSuccess = true;
        long startTime = System.currentTimeMillis();
        Map<String, String> map = new HashMap<String, String>();
        listener:
        while (true) {
            if (System.currentTimeMillis() - startTime > listenerTimeout) {
                isSuccess = false;
                break;
            }
            // 事务节点
            List<String> childreList = zkClient.getChildren(transactionPath);
            if (childreList == null || childreList.size() != transactionCount) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
            for (int i = 0; i < childreList.size(); i++) {
                String node = childreList.get(i);
                String subPath = transactionPath + "/" + node;
                String data = null;
                try {
                    data = zkClient.readData(subPath, true);
                } catch (Exception e) {

                }
                map.put(node, data);
                // 确认当前节点事务是否完成
                if (data != null && !data.isEmpty()) {
                    isSuccess &= "1".equals(data);
                } else if (data == null || data.isEmpty()) {
                    continue listener;
                }
            }
            // 是否为所有节点状态
            if (childreList.size() == transactionCount || !isSuccess) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        logger.debug(map.toString());
        return isSuccess;
    }

    private void complete(String transactionPath, Integer transactionCount, boolean isException) {
        if (transactionCount == null) {
            return;
        }
        long startTime = System.currentTimeMillis();
        listener:
        while (true) {
            if (System.currentTimeMillis() - startTime > listenerTimeout) {
                break;
            }
            // 事务节点
            List<String> childrenList = zkClient.getChildren(transactionPath);
            if (!isException) {
                if (childrenList == null || childrenList.size() != transactionCount) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    continue;
                }
            }
            boolean okey = true;
            for (int i = 0; i < childrenList.size(); i++) {
                String node = childrenList.get(i);
                String subPath = transactionPath + "/" + node;
                String data = null;
                try {
                    data = zkClient.readData(subPath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                okey = okey & ("2".equals(data));
            }
            // 是否为所有节点状态
            if (isException & okey) {
                break;
            }
            if ((!isException) && childrenList.size() == transactionCount && okey) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    class ConsumerSideThread extends Thread {
        private ProceedingJoinPoint point;
        private int count;
        private String znode;
        private CountDownLatch countDownLatch;

        public ConsumerSideThread(String znode, ProceedingJoinPoint joinPoint, int count, CountDownLatch countDownLatch) {
            this.point = joinPoint;
            this.count = count;
            this.znode = znode;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void run() {
            this.setName("ConsumerSide");
            consumerSideTransactionListener(znode, point, count);
            countDownLatch.countDown();
        }
    }
}
