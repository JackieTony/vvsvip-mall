package com.vvsvip.common.lock;

import com.vvsvip.common.tx.DistributedTransactionParams;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DistributedLock implements Lock, Watcher {

    private ZooKeeper zookeeper;
    //分布式锁根节点
    private String lockRoot = DistributedTransactionParams.ZK_LOCK.getValue();
    private String lockName;//竞争资源的标志
    private String waitNode;//等待前一个锁
    private String lockNode;//当前锁
    private CountDownLatch latch;//计数器
    private int sessionTimeout = 300;
    private List<Exception> exception = new ArrayList<Exception>();


    /**
     * 创建分布式锁,使用前请确认config配置的zookeeper服务可用
     */
    public DistributedLock(ZooKeeper zookeeper) {
        this.zookeeper = zookeeper;
    }

    @PostConstruct
    public void init() {
        try {
            Stat stat = zookeeper.exists(lockRoot, false);
            if (stat == null) {
                zookeeper.create(lockRoot, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public DistributedLock(String config, String lockName) {
        setLockName(lockName);
        // 创建一个与服务器的连接
        try {
            zookeeper = new ZooKeeper(config, sessionTimeout, this);
            Stat stat = zookeeper.exists(lockRoot, false);
            if (stat == null) {
                // 创建根节点
                zookeeper.create(lockRoot, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (IOException | KeeperException | InterruptedException e) {
            exception.add(e);

        }
    }

    /**
     * 设置竞争资源标志
     * lockName中不能包含单词lock
     *
     * @param lockName
     */
    public void setLockName(String lockName) {
        this.lockName = lockName;
    }


    /**
     * 节点监视器读写锁
     */
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    /**
     * 是否已经出发监视器
     */
    private boolean isProcessed = false;

    /**
     * zookeeper节点的监视器
     */
    @Override
    public void process(WatchedEvent event) {
        try {
            readWriteLock.writeLock().lock();
            if (this.latch != null) {
                this.latch.countDown();
            }
            isProcessed = true;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    @Override
    public void lock() {
        if (exception.size() > 0) {
            throw new LockException(exception.get(0));
        }
        try {
            if (this.tryLock()) {
                // System.out.println("Thread " + Thread.currentThread().getId() + " " + lockNode + " get lock true");
                return;
            } else {
                waitForLock(waitNode, sessionTimeout, TimeUnit.MILLISECONDS);//等待锁
            }
        } catch (KeeperException | InterruptedException e) {
            throw new LockException(e);
        }
    }

    @Override
    public boolean tryLock() {
        try {
            String splitStr = "_lock_";
            if (lockName.contains(splitStr))
                throw new LockException("lockName can not contains \\u000B");
            //创建临时子节点
            String nodeName = lockRoot + "/" + lockName + splitStr;
            lockNode = zookeeper.create(nodeName, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

            // System.out.println(nodeName + " is created ");
            //取出所有子节点
            List<String> subNodes = zookeeper.getChildren(lockRoot, false);

            //取出所有lockName的锁
            List<String> lockObjNodes = new ArrayList<String>();
            for (String node : subNodes) {
                String _node = node.split(splitStr)[0];
                if (_node.equals(lockName)) {
                    lockObjNodes.add(node);
                }
            }

            Collections.sort(lockObjNodes);

            // System.out.println(lockNode + "==" + lockObjNodes.get(0));
            if (lockNode.equals(lockRoot + "/" + lockObjNodes.get(0))) {
                //如果是最小的节点,则表示取得锁
                return true;
            }
            //如果不是最小的节点，找到比自己小1的节点
            String subMyZnode = lockNode.substring(lockNode.lastIndexOf("/") + 1);

            waitNode = lockObjNodes.get(Collections.binarySearch(lockObjNodes, subMyZnode) - 1);

        } catch (KeeperException | InterruptedException e) {
            throw new LockException(e);
        }
        return false;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) {
        try {
            if (this.tryLock()) {
                return true;
            }
            return waitForLock(waitNode, time, unit);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    private boolean waitForLock(String preLockNode, long waitTime, TimeUnit unit) throws InterruptedException, KeeperException {
        Stat stat = zookeeper.exists(lockRoot + "/" + preLockNode, true);
        //判断比自己小一个数的节点是否存在,如果不存在则无需等待锁,同时注册监听
        if (stat != null) {
            // System.out.println("Thread " + Thread.currentThread().getId() + " waiting for " + lockRoot + "/" + preLockNode);
            this.latch = new CountDownLatch(1);
            try {
                readWriteLock.readLock().lock();
                if (!isProcessed) {
                    this.latch.await(waitTime, unit);
                }
            } finally {
                readWriteLock.readLock().unlock();
            }
            this.latch = null;
        }
        return true;
    }

    /**
     * 解锁
     */
    public void unlock() {
        try {
            // System.out.println("unlock " + lockNode);
            zookeeper.delete(lockNode, -1);
            lockNode = null;
            zookeeper.close();
        } catch (InterruptedException | KeeperException e) {
            e.printStackTrace();
        }
    }

    public void lockInterruptibly() throws InterruptedException {
        this.lock();
    }

    public Condition newCondition() {
        return null;
    }

    public class LockException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LockException(String e) {
            super(e);
        }

        LockException(Exception e) {
            super(e);
        }
    }

}