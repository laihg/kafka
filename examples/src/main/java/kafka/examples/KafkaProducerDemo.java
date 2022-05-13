package kafka.examples;

import org.apache.kafka.common.errors.TimeoutException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author hongguang.lai
 * @date 2022/5/1 18:03
 */
public class KafkaProducerDemo {

    public static void main(String[] args) throws InterruptedException {
        //boolean isAsync = args.length == 0 || !args[0].trim().equalsIgnoreCase("sync");
        boolean isAsync = false;
        CountDownLatch latch = new CountDownLatch(1);
        Producer producerThread = new Producer(KafkaProperties.TOPIC, isAsync, null, false, 10, -1, latch);
        producerThread.start();

        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new TimeoutException("Timeout after 5 minutes waiting for demo producer and consumer to finish");
        }
    }
}
