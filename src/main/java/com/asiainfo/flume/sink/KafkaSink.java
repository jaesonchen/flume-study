package com.asiainfo.flume.sink;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.springframework.kafka.core.KafkaTemplate;

import com.asiainfo.flume.util.KafkaUtils;
import com.google.common.base.Charsets;

/**
 * Kafka Sink 自定义实现
 * 
 * @author       zq
 * @date         2018年1月11日  下午4:34:43
 * Copyright: 	  北京亚信智慧数据科技有限公司
 */
public class KafkaSink extends AbstractSink implements Configurable {

    KafkaTemplate<Integer, String> template;
    String topic;
    
    /* 
     * @see org.apache.flume.Sink#process()
     */
    @Override
    public Status process() throws EventDeliveryException {
        
        Channel channel = getChannel();
        Transaction tx = channel.getTransaction();
        try {
            Event event = channel.take();
            if (event == null) {
                return Status.BACKOFF;
            }
            tx.begin();
            KafkaUtils.send(this.template, this.topic, new String(event.getBody(), Charsets.UTF_8));
            tx.commit();
            return Status.READY;
        } catch(Exception ex) {
            tx.rollback();
            return Status.BACKOFF;
        } finally {
            tx.close();
        }
    }

    /*
     * @see org.apache.flume.conf.Configurable#configure(org.apache.flume.Context)
     */
    @Override
    public void configure(Context context) {
        this.template = KafkaUtils.<Integer, String>createTemplate(context);
        this.topic = context.getString("kafka.topic", "default");
    }
}
