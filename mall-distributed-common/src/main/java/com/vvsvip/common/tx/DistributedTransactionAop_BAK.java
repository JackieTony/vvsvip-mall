package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import com.vvsvip.common.security.EncryptUtil;
import com.vvsvip.common.tx.annotation.DistributedTransaction;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Created by ADMIN on 2017/4/24.
 */
@Order(6)
public class DistributedTransactionAop_BAK {

    Logger logger = LoggerFactory.getLogger(DistributedTransactionAop_BAK.class);

    /**
     * zookeeper namespace间隔
     */
    private static final String INTERVAL = DistributedTransactionParams.INTERVAL.getValue();
    private String namespace;
    private String consumerSideNode;

    @Autowired
    private ZkClient zkClient;


    private CountDownLatch countDownLatch;

    static final long listenerTimeout = 30000;
    static final TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    private String providerSideNode;
    /**
     * 节点监视器读写锁
     */
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    /**
     * 是否已经出发监视器
     */
    private boolean isProcessed = false;

    /**
     * 服务切点
     */
    @Pointcut("execution(public * com.vvsvip.dubbo..*.*(..))")
    private void transactionMethod() {
    }

    /**
     * 异常抛出 回滚事务
     */
    @AfterThrowing("transactionMethod()")
    public void doAfterThrow() throws Exception {
        logger.info("异常拦截开始回滚事务");
        if (RpcContext.getContext().isConsumerSide()) {
            String transactionPath = consumerSideNode;
            try {
                if (transactionPath != null) {
                    boolean stat = zkClient.exists(DistributedTransactionParams.ZK_PATH.getValue());
                    if (stat) {
                        stat = zkClient.exists(transactionPath);
                        if (stat) {
                            try {
                                zkClient.writeData(transactionPath, "0", -1);
                                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                                throw new Exception("rollback " + transactionPath);
                            } finally {
                                zkClient.deleteRecursive(transactionPath);
                            }
                        }
                    }
                }
            } catch (KeeperException | InterruptedException e) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw e;
            }
        } else {
            if (providerSideNode != null) {
                boolean stat = zkClient.exists(providerSideNode);
                if (stat) {
                    zkClient.writeData(providerSideNode, "1", -1);
                }
            }
        }
    }

    @Around("transactionMethod()")
    public Object doAround(final ProceedingJoinPoint joinPoint) throws Throwable {
        Boolean exec = Boolean.parseBoolean(String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.EXECUTE_SIGN)));
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method targetMethod = methodSignature.getMethod();
        DistributedTransaction distributedTransaction = targetMethod.getAnnotation(DistributedTransaction.class);
        int transactionCount = 0;
        boolean isConsumerSide = false;

        CountDownLatch countDownLatch = null;
        if (exec) {
            if (distributedTransaction != null) {
                //isConsumerSide = distributedTransaction.consumerSide();
            }

            // 消费者节点 准备开启事务
            if (RpcContext.getContext().isConsumerSide() || isConsumerSide) {
                if (distributedTransaction != null) {
                    transactionCount = distributedTransaction.value();
                    if (transactionCount > 0) {
                        logger.info("ConsumerSide doAround begin");

                        beforeConsumerSide(joinPoint);
                    }
                }
            } else {
                logger.info("ProviderSide doAround begin");
                beforeProviderSide(joinPoint);
            }
            // 事务尾声处理
            if (isConsumerSide) {
                if (distributedTransaction != null && transactionCount > 0) {
                    countDownLatch = new CountDownLatch(1);
                    new ConsumerSideTread(joinPoint, transactionCount, countDownLatch).start();
                }
            }
        }

        // 执行当前方法
        Object object = joinPoint.proceed();
        if (countDownLatch != null) {
            countDownLatch.await(listenerTimeout, TimeUnit.MILLISECONDS);
        }
        if (exec) {
            if (!isConsumerSide
                    &&
                    DistributedTransactionParams.YES.getValue().equals(RpcContext.getContext()
                            .getAttachment(DistributedTransactionParams.TRANSACTION_STATUS.getValue()))) {
                afterProviderSide(joinPoint);
                logger.info("ProviderSide doAround success");

            }
        }
        return object;
    }

    /**
     * 消费者事务准备
     *
     * @param joinPoint
     * @throws Exception
     */
    private void beforeConsumerSide(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean stat = zkClient.exists(DistributedTransactionParams.ZK_PATH.getValue());
        if (!stat) {
            zkClient.create(DistributedTransactionParams.ZK_PATH.getValue(), "", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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
                .append(INTERVAL).append(System.currentTimeMillis())
                .append(INTERVAL).append(clazzName)
                .append(INTERVAL).append(methodName)
                .append(INTERVAL).append(EncryptUtil.encodeBase64(params));
        this.namespace = URLEncoder.encode(namespaceBuffer.toString(), "UTF-8");


        RpcContext.getContext()
                .setAttachment(
                        DistributedTransactionParams.TRANSACTION_ZNODE.getValue()
                        , this.namespace);

        // 创建事务节点
        consumerSideNode = zkClient.create(DistributedTransactionParams.ZK_PATH + "/" + this.namespace, "", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    }


    /**
     * 消费者事务处理
     *
     * @param joinPoint
     * @param transactionCount
     */
    private void afterConsumerSide(ProceedingJoinPoint joinPoint, int transactionCount) throws Throwable {
        logger.info("afterConsumerSide begin");
        String transactionPath = DistributedTransactionParams.ZK_PATH + "/" + this.namespace;
        try {
            boolean isSuccess = true;

            long startTime = System.currentTimeMillis();
            while (true) {
                // 事务节点
                List<String> childreList = zkClient.getChildren(transactionPath);
                for (int i = 0; i < childreList.size(); i++) {
                    String node = childreList.get(i);
                    String subPath = transactionPath + "/" + node;
                    try {
                        String data = zkClient.readData(subPath, true);
                        // 确认当前节点事务是否完成
                        if (data != null && !data.isEmpty()) {
                            isSuccess &= Integer.valueOf(data) == 1;
                        }
                    } catch (Exception e) {

                    }
                }
                // 是否为所有节点状态
                if (childreList.size() == transactionCount || !isSuccess) {
                    break;
                }
                if (System.currentTimeMillis() - startTime > listenerTimeout) {
                    isSuccess = false;
                }
                Thread.sleep(50);
            }
            if (isSuccess) {
                zkClient.writeData(transactionPath, "1", -1);
            } else {
                zkClient.writeData(transactionPath, "0", -1);
                //TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw new Exception("Rollback " + transactionPath);
            }
        } catch (KeeperException | InterruptedException e) {
            throw e;
        } finally {
            //zkClient.deleteRecursive(transactionPath);
            logger.info("afterConsumerSide end");
        }
    }


    /**
     * 生产者事务准备
     *
     * @param joinPoint
     * @throws Exception
     */
    private void beforeProviderSide(ProceedingJoinPoint joinPoint) throws Throwable {
        logger.info("begin");
        String znode = RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_ZNODE.getValue());

        if (znode == null) {
            logger.info("znode null exit");
            return;
        }

        String transactionPath = DistributedTransactionParams.ZK_PATH.getValue() + "/" + znode;

        boolean statRoot = zkClient.exists(DistributedTransactionParams.ZK_PATH.getValue());
        if (!statRoot) {
            zkClient.create(DistributedTransactionParams.ZK_PATH.getValue(), "", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        //添加根节点监听
        zkClient.setZkSerializer(new ZkSerializer() {
            @Override
            public byte[] serialize(Object data) throws ZkMarshallingError {
                return ((String) data).getBytes();
            }

            @Override
            public Object deserialize(byte[] bytes) throws ZkMarshallingError {
                byte[] data = Arrays.copyOfRange(bytes, 7, bytes.length);
                return new String(data);
            }
        });
        zkClient.subscribeDataChanges(transactionPath, new IZkDataListener() {
            @Override
            public void handleDataChange(String dataPath, Object data) throws Exception {
                System.out.println("监听被触发了");
                try {
                    readWriteLock.writeLock().lock();
                    isProcessed = true;
                    if (countDownLatch != null) {
                        countDownLatch.countDown();
                    }
                } finally {
                    readWriteLock.writeLock().unlock();
                    System.out.println("解锁成功");

                }
            }

            @Override
            public void handleDataDeleted(String dataPath) throws Exception {
                System.out.println(dataPath);
            }
        });

        String data = zkClient.readData(transactionPath, true);
        if (data != null && "0".equals(data)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new Throwable("事务回滚：" + znode);
        }


        // zkNamespace
        StringBuffer namespace = new StringBuffer();
        // 获取本地IP地址
        String ip = InetAddress.getLocalHost().getHostAddress();
        // 获取当前方法所在的类
        Object target = joinPoint.getTarget();
        String clazzName = target.getClass().getName();

        // 获取当前方法的名字
        String methodName = joinPoint.getSignature().getName();
        // 获取当前方法的所有参数
        Object[] args = joinPoint.getArgs();
        String paramsStr = "";
        if (args != null && args.length > 0) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(args);
            byte[] params = byteArrayOutputStream.toByteArray();
            paramsStr = EncryptUtil.encodeBase64(params);
        }
        // 将所持有参数转为二进制数据

        // 拼接transaction znode namespace
        namespace.append(ip)
                .append(INTERVAL).append(System.currentTimeMillis())
                .append(INTERVAL).append(clazzName)
                .append(INTERVAL).append(methodName)
                .append(INTERVAL).append(paramsStr);
        String namespaceStr = URLEncoder.encode(namespace.toString(), "UTF-8");
        // 创建事务节点
        providerSideNode = zkClient.create(transactionPath + "/" + namespaceStr, "", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        logger.info("beforeProviderSide success");

    }

    /**
     * 生产者事务处理
     *
     * @param joinPoint
     */
    private void afterProviderSide(ProceedingJoinPoint joinPoint) throws Exception {
        logger.info(joinPoint.getSignature().getName() + "开始处理");
        String znode = RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_ZNODE.getValue());
        if (znode == null) {
            logger.info("znode null exit");
            return;
        }

        zkClient.writeData(providerSideNode, "1", -1);

        if (!isProcessed) {
            countDownLatch = new CountDownLatch(1);
            TransactionMessageAop.threadParam.get().put(TransactionMessageAop.COUNT_DOWN_LATCH, countDownLatch);
            TransactionMessageAop.threadParam.get().put(TransactionMessageAop.LOCK, readWriteLock);
            /*
            try {
                logger.info("读锁打开");
                readWriteLock.readLock().lock();
                if (!isProcessed) {
                    //
                    countDownLatch.await(listenerTimeout, timeUnit);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                readWriteLock.readLock().unlock();
                logger.info("读锁关闭");
            }
            String transactionPath = DistributedTransactionParams.ZK_PATH + "/" + znode;
            String data = zkClient.readData(transactionPath);
            if (data == null || "0".equals(data)) {
                throw new Exception("rollback " + providerSideNode);
            }
            */
        }


        logger.info(joinPoint.getSignature().getName() + "已完成,等待事务处理");
    }

    class ConsumerSideTread extends Thread {
        private ProceedingJoinPoint point;
        private int count;
        private CountDownLatch countDownLatch;

        public ConsumerSideTread(ProceedingJoinPoint joinPoint, int count, CountDownLatch countDownLatch) {
            this.point = joinPoint;
            this.count = count;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void run() {
            try {
                afterConsumerSide(point, count);
            } catch (Throwable throwable) {
                logger.error("Transaction Listener Exception", throwable);
            }
            countDownLatch.countDown();
        }
    }
}
