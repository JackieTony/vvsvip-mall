package com.vvsvip.common.tx;

import com.vvsvip.common.dao.TransactionMessageMapper;

/**
 * Created by blues on 2017/4/25.
 */
public class TransactionCompensateThread extends Thread {

    private TransactionMessageMapper transactionMessageMapper;

    public TransactionCompensateThread(TransactionMessageMapper transactionMessageMapper) {
        this.transactionMessageMapper = transactionMessageMapper;
    }

    public void run() {
        
    }
}
