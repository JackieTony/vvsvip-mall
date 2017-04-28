package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import com.vvsvip.common.security.EncryptUtil;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by ADMIN on 2017/4/27.
 */
public class DistributedTransactionProviderSideAOP {
    private Logger logger = LoggerFactory.getLogger(DistributedTransactionAop.class);

    /**
     * zookeeper namespace间隔
     */
    private static final String INTERVAL = DistributedTransactionParams.INTERVAL.getValue();

    @Autowired
    private ZkClient zkClient;

    private String providerSideNode;
    private String znode;

    private CountDownLatch countDownLatch = new CountDownLatch(1);

    static final long listenerTimeout = 30000;
    static final TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    /**
     * 节点监视器读写锁
     */
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    /**
     * 是否已经出发监视器
     */
    private boolean isProcessed = false;

    /*
    public void doAfterThrow() {
        providerSideNode = String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.CURRENT_ZNODE));
        boolean stat = zkClient.exists(providerSideNode);
        if (stat) {
            zkClient.writeData(providerSideNode, "-1", -1);
        } else {
            zkClient.create(providerSideNode, "-1", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
        throw new RuntimeException("事务处理结束");
    }
*/

    public Object doAround(final ProceedingJoinPoint joinPoint) throws Throwable {
        logger.info("ProviderSide doAround begin");
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.IS_CONSUMER_SIDE, false);
        znode = RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_ZNODE.getValue());
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.TRANSACTION_ZNODE_PATH, znode);

        beforeProviderSide(joinPoint);
        Object object = joinPoint.proceed();
        return object;
    }

    /**
     * 生产者事务准备
     *
     * @param joinPoint
     * @throws Exception
     */
    private void beforeProviderSide(ProceedingJoinPoint joinPoint) throws Throwable {
        logger.info("beforeProviderSide begin");
        if (znode == null) {
            logger.info("znode null exit");
            return;
        }
        String transactionPath = znode;

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
        //添加根节点监听
        zkClient.subscribeDataChanges(transactionPath, new IZkDataListener() {
            @Override
            public void handleDataChange(String dataPath, Object data) throws Exception {
                System.out.println("监听器触发===========================" + data);
                countDownLatch.countDown();
            }

            @Override
            public void handleDataDeleted(String dataPath) throws Exception {
                System.out.println(dataPath);
            }
        });

        String data = zkClient.readData(transactionPath, true);
        if (data != null && "-1".equals(data)) {
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
        zkClient.create(transactionPath + "/" + namespaceStr, "-1", ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        providerSideNode = transactionPath + "/" + namespaceStr;
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.CURRENT_ZNODE, providerSideNode);
        logger.info("beforeProviderSide success");
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.COUNT_DOWN_LATCH, countDownLatch);
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.LOCK, readWriteLock);
        TransactionMessageAop.threadParam.get().put(TransactionMessageAop.CURRENT_ZNODE, transactionPath + "/" + namespaceStr);
    }
}
