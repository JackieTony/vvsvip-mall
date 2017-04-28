package com.vvsvip.dubbo.impl;


import com.vvsvip.common.dao.TransactionMessageMapper;
import com.vvsvip.core.dao.UserMapper;
import com.vvsvip.core.model.User;
import com.vvsvip.shop.test.service.IHelloWorldManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;


/**
 * Created by blues on 2017/4/19.
 */
@org.springframework.stereotype.Service
//@com.alibaba.dubbo.config.annotation.Service(loadbalance = "random")
public class HelloWorldManager implements IHelloWorldManager {

    @Autowired
    private UserMapper userMapper;

    private static int i = 0;

    @Override
    public void sayHelloWorld() {
        User user = new User();
        user.setUsername(new SecureRandom().nextInt() + "");
        user.setPassword(i++ + "");
        userMapper.insert(user);
        System.out.println("Hello dubbo");
    }
}
