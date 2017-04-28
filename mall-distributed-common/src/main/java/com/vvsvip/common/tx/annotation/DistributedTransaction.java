package com.vvsvip.common.tx.annotation;


/**
 * 开启分布式事务
 * Created by ADMIN on 2017/4/25.
 */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD})
public @interface DistributedTransaction {
    int value() default -1;

   // boolean consumerSide() default false;
}
