package com.asiainfo.flume.source;

import java.util.HashMap;
import java.util.Map;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.AbstractPollableSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.config.ContainerProperties;

import com.asiainfo.flume.util.KafkaUtils;
import com.google.common.base.Charsets;

/**
 * kafka Source 自定义实现
 * 
 * @author       zq
 * @date         2018年1月11日  下午2:07:52
 * Copyright: 	  北京亚信智慧数据科技有限公司
 */
public class KafkaSource extends AbstractPollableSource {

    KafkaMessageListenerContainer<Integer, String> container;
    String[] topics;
    
    /* 
     * @see org.apache.flume.source.AbstractPollableSource#doProcess()
     */
    @Override
    protected Status doProcess() throws EventDeliveryException {
        
        try {
            //非监听方式在这里取kafka ConsumerRecord封装event，调用getChannelProcessor().processEvent(event);
            return Status.READY;
        } catch (Exception ex) {
            // ignore
        }
        return Status.BACKOFF;
    }

    /* 
     * @see org.apache.flume.source.BasicSourceSemantics#doConfigure(org.apache.flume.Context)
     */
    @Override
    protected void doConfigure(Context context) throws FlumeException {
        
        this.topics = context.getString("kafka.topics").split("\\s+");
        ContainerProperties containerProps = new ContainerProperties(this.topics);
        containerProps.setMessageListener(new MessageListener<Integer, String>() {
            @Override
            public void onMessage(ConsumerRecord<Integer, String> message) {
                Event event = new SimpleEvent();
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
                event.setHeaders(headers);
                event.setBody(message.value().getBytes(Charsets.UTF_8));
                getChannelProcessor().processEvent(event);
            }
        });
        this.container = KafkaUtils.<Integer, String>createContainer(containerProps, context);
    }

    /* 
     * @see org.apache.flume.source.BasicSourceSemantics#doStart()
     */
    @Override
    protected void doStart() throws FlumeException {
        this.container.start();
    }

    /*
     * @see org.apache.flume.source.BasicSourceSemantics#doStop()
     */
    @Override
    protected void doStop() throws FlumeException {
        this.container.stop();
    }
}
