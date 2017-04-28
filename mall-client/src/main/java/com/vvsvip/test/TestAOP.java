package com.vvsvip.test;

import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Created by ADMIN on 2017/4/26.
 */
public class TestAOP {

    public static void main(String[] args) throws InterruptedException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("classpath:spring-cloud.xml", "classpath:spring-data.xml");
        context.start();
        for (int i = 0; i < 3; i++) {
            TestTransaction testTransaction = context.getBean(TestTransaction.class);
            new TestThread(testTransaction).start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {

                System.out.println("hook running...");
            }
        }));
    }

    static class TestThread extends Thread {
        private TestTransaction testTransaction;

        public TestThread(TestTransaction helloWorldManager) {
            this.testTransaction = helloWorldManager;
        }

        @Test
        public void run() {
            try {
                testTransaction.test();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
