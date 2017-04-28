package com.vvsvip.common.tx;

import com.alibaba.dubbo.rpc.RpcContext;
import com.vvsvip.common.model.TransactionMessage;
import com.vvsvip.common.dao.TransactionMessageMapper;
import com.vvsvip.common.security.EncryptUtil;
import com.vvsvip.common.tx.annotation.DistributedTransaction;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Hashtable;
import java.util.UUID;

/**
 * Created by blues on 2017/4/25.
 */
public class TransactionMessageAop {
    Logger logger = LoggerFactory.getLogger(TransactionMessageAop.class);
    static ThreadLocal<DistributedTransactionParams> paramsThreadLocal = new ThreadLocal<DistributedTransactionParams>();
    static ThreadLocal<Hashtable<String, Object>> threadParam = new ThreadLocal<Hashtable<String, Object>>();

    static final String EXECUTE_SIGN = "exec";                                 //是否可以执行
    static final String IS_CONSUMER_SIDE = "IS_CONSUMER_SIDE";                 //是否为消费者
    static final String UUID_KEY = "UUID_KEY";  // 当前事务的UUID
    static final String COUNT_DOWN_LATCH = "COUNT_DOWN_LATCH";                 // 当前线程的统计所
    static final String LOCK = "READ_WRITE_LOCK";                              //当前线程的读写所
    static final String TRANSACTION_ZNODE_PATH = "TRANSACTION_ZNODE_PATH";     // 消费者事务节点
    static final String CURRENT_ZNODE = "CURRENT_ZNODE";                       //提供者节点
    static final String COMMIT_STATUS = "1";                                   //当前事务已完成
    static final String ROLLBACK_STATUS = "-1";                                //当前事务已回滚
    static final String IS_DISTRIBUTED = "IS_DISTRIBUTED";                     //是分布式事务
    static String TRANSACTION_COUNT = "TRANSACTION_COUNT";                     //分布式事务总个数
    @Autowired
    private TransactionMessageMapper transactionMessageMapper;

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        logger.debug("进入消息环绕");

        boolean exec = executable(joinPoint);

        if (exec && DistributedTransactionSupport.isDistributedTransaction() && !DistributedTransactionSupport.isConsumerSide()) {
            RpcContext.getContext().getAttachment(DistributedTransactionParams.TRANSACTION_ZNODE.getValue());
            logger.debug("保存当前方法的参数");
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

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(args);
            // 将所持有参数转为二进制数据
            byte[] params = byteArrayOutputStream.toByteArray();
            String paramsStr = EncryptUtil.encodeBase64(params);
            String uuid = UUID.randomUUID().toString().replace("-", "");
            TransactionMessageAop.threadParam.get().put(UUID_KEY, uuid);
            // 该消息存入数据库
            TransactionMessage transactionMessage = new TransactionMessage(String.valueOf(threadParam.get().get(UUID_KEY)), ip, clazzName, methodName, paramsStr);
            transactionMessageMapper.insert(transactionMessage);
            logger.info("消息记录已入库");

        }


        Object obj = joinPoint.proceed();

        /*
        if (exec && TransactionMessageAop.paramsThreadLocal.get() != null) {
            if (DistributedTransactionParams.ROLL_BACK.getValue().equals(TransactionMessageAop.paramsThreadLocal.get().getValue())) {
                TransactionMessage transactionMessage = transactionMessageMapper.selectByUUID(String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.UUID_KEY)));
                transactionMessage.setStatus(TransactionMessageAop.ROLLBACK_STATUS);
                transactionMessageMapper.updateByPrimaryKey(transactionMessage);
                logger.debug("事务回滚");
            } else if (DistributedTransactionParams.COMMITED.getValue().equals(TransactionMessageAop.paramsThreadLocal.get().getValue())) {
                TransactionMessage transactionMessage = transactionMessageMapper.selectByUUID(String.valueOf(TransactionMessageAop.threadParam.get().get(TransactionMessageAop.UUID_KEY)));
                transactionMessage.setStatus(TransactionMessageAop.COMMIT_STATUS);
                transactionMessageMapper.updateByPrimaryKey(transactionMessage);
                logger.debug("事务提交");
            }
        }
        logger.debug("切面方法执行完毕");
        */

        return obj;
    }

    static void initContext(ProceedingJoinPoint joinPoint) {
        executable(joinPoint);
    }

    static boolean executable(ProceedingJoinPoint joinPoint) {
        boolean exec = true;
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method targetMethod = methodSignature.getMethod();
        Object target = joinPoint.getTarget();
        if (TransactionMessageAop.threadParam.get() == null) {
            TransactionMessageAop.threadParam.set(new Hashtable<String, Object>());
        }
        DistributedTransaction distributedTransaction = targetMethod.getAnnotation(DistributedTransaction.class);
        if (distributedTransaction != null) {
            TransactionMessageAop.threadParam.get().put(IS_DISTRIBUTED, true);
        }
        // 如果是构造函数 不执行
        if (targetMethod.getName() == target.getClass().getConstructors()[0].getName()) {
            exec = false;
            TransactionMessageAop.threadParam.get().put(EXECUTE_SIGN, exec);
        } else {
            TransactionMessageAop.threadParam.get().put(EXECUTE_SIGN, true);
        }
        return exec;
    }
}
