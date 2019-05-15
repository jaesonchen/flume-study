package com.asiainfo.flume.sink;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import com.asiainfo.flume.util.KafkaUtils;
import com.google.common.base.Throwables;

/**   
 * @Description: Kafka batch sink batch实现
 * 
 * @author chenzq  
 * @date 2019年4月21日 下午6:32:24
 * @version V1.0
 * @Copyright: Copyright(c) 2019 jaesonchen.com Inc. All rights reserved. 
 */
public class KafkaBatchSink extends AbstractSink implements Configurable {

    final Properties kafkaProps = new Properties();
    KafkaProducer<String, byte[]> producer;
    List<Future<RecordMetadata>> kafkaFutures;
    String topic;
    int batchSize;
    
    /*
     * @see org.apache.flume.Sink#process()
     */
    @Override
    public Status process() throws EventDeliveryException {
        
        Status result = Status.READY;
        Channel channel = getChannel();
        Transaction transaction = null;
        Event event = null;
        long processedEvents = 0;
        try {
            // begin transaction
            transaction = channel.getTransaction();
            transaction.begin();

            kafkaFutures.clear();
            for (; processedEvents < batchSize; processedEvents++) {
                event = channel.take();
                if (event == null) {
                    // no events available in channel
                    if (processedEvents == 0) {
                        result = Status.BACKOFF;
                    }
                    break;
                }
                
                byte[] eventBody = event.getBody();
                Map<String, String> headers = event.getHeaders();
                String eventKey = headers.get("key");
                ProducerRecord<String, byte[]> record = new ProducerRecord<String, byte[]>(topic, eventKey, eventBody);
                try {
                    kafkaFutures.add(producer.send(record));
                } catch (Exception ex) {
                    throw new EventDeliveryException("Could not send event", ex);
                }
            }
            producer.flush();
            // publish batch and commit.
            if (processedEvents > 0) {
                for (Future<RecordMetadata> future : kafkaFutures) {
                    future.get();
                }
            }
            transaction.commit();
        } catch (Exception ex) {
            result = Status.BACKOFF;
            if (transaction != null) {
                try {
                    kafkaFutures.clear();
                    transaction.rollback();
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
            }
            throw new EventDeliveryException("Failed to publish events", ex);
        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
        return result;
    }

    @Override
    public synchronized void start() {
        this.producer = new KafkaProducer<String, byte[]>(kafkaProps);
        super.start();
    }

    @Override
    public synchronized void stop() {
        this.producer.close();
        super.stop();
    }

    /*
     * @see org.apache.flume.conf.Configurable#configure(org.apache.flume.Context)
     */
    @Override
    public void configure(Context context) {
        this.topic = context.getString("kafka.topic", "default");
        this.batchSize = context.getInteger("kafka.batchSize", 1000);
        this.kafkaFutures = new LinkedList<Future<RecordMetadata>>();
        
        kafkaProps.putAll(context.getSubProperties("kafka.producer"));
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, context.getString("kafka.bootstrap.servers"));
        kafkaProps.put(ProducerConfig.BATCH_SIZE_CONFIG, context.getInteger("kafka.batchSize", 16384));
        kafkaProps.put(ProducerConfig.ACKS_CONFIG, context.getInteger("kafka.acks", 1));
        kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KafkaUtils.forName(context, "kafka.key.serializer.class", StringSerializer.class));
        kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaUtils.forName(context, "kafka.value.serializer.class", StringSerializer.class));
    }
}
