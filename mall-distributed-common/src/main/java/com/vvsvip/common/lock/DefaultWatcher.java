package com.vvsvip.common.lock;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认Watcher
 * Created by ADMIN on 2017/4/25.
 */
public class DefaultWatcher implements Watcher {

    private static Logger logger = LoggerFactory.getLogger(DefaultWatcher.class);

    @Override
    public void process(WatchedEvent watchedEvent) {
        logger.debug("WatchedEvent:" + watchedEvent.toString());
    }
}
