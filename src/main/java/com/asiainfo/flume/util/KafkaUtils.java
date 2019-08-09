package com.asiainfo.flume.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * Kafka consumer/producer 工具类
 * 
 * @author       zq
 * @date         2018年1月11日  上午11:38:17
 * Copyright: 	  北京亚信智慧数据科技有限公司
 */
public class KafkaUtils {
    
    // kafka消费者容监听容器，消费所有分区，最大并发数超过分区数的，超过部分无效
    public static <K extends java.io.Serializable, V extends java.io.Serializable> ConcurrentMessageListenerContainer<K, V> 
        createConcurrentContainer(ContainerProperties cp, Context context) {
        
        Map<String, Object> props = consumerProps(context);
        DefaultKafkaConsumerFactory<K, V> factory = new DefaultKafkaConsumerFactory<>(props);
        ConcurrentMessageListenerContainer<K, V> container = new ConcurrentMessageListenerContainer<>(factory, cp);
        container.setConcurrency(context.getInteger("kafka.concurrent", 3));
        return container;
    }

    // kafka消费者容监听容器，消费所有分区
    public static <K extends java.io.Serializable, V extends java.io.Serializable> KafkaMessageListenerContainer<K, V> 
        createContainer(ContainerProperties cp, Context context) {
        
        Map<String, Object> props = consumerProps(context);
        DefaultKafkaConsumerFactory<K, V> factory = new DefaultKafkaConsumerFactory<>(props);
        KafkaMessageListenerContainer<K, V> container = new KafkaMessageListenerContainer<>(factory, cp);
        return container;
    }
    
    // kafkaTemplate生产者模板
    public static <K extends java.io.Serializable, V extends java.io.Serializable>  KafkaTemplate<K, V> createTemplate(Context context) {
        
        Map<String, Object> producerProps = producerProps(context);
        ProducerFactory<K, V> factory = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<K, V> template = new KafkaTemplate<>(factory);
        return template;
    }

    // 发送到kafka
    public static <K extends java.io.Serializable, V extends java.io.Serializable> ListenableFuture<SendResult<K, V>> 
        send(KafkaTemplate<K, V> template, String topic, V data) {
        
        return template.send(topic, data);
    }
    
    // 发送到kafka
    public static <K extends java.io.Serializable, V extends java.io.Serializable> List<ListenableFuture<SendResult<K, V>>> 
        send(KafkaTemplate<K, V> template, String topic, List<V> list) {
        
        List<ListenableFuture<SendResult<K, V>>> result = new ArrayList<>();
        for (V message : list) {
            result.add(template.send(topic, message));
        }
        template.flush();
        return result;
    }
    
    // consumer配置项，通常从Context里读取配置
    private static Map<String, Object> consumerProps(Context ctx) {
        
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ctx.getString("kafka.bootstrap.servers"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, ctx.getString("kafka.group.id", "flume"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, forName(ctx, "kafka.key.deserializer.class", StringDeserializer.class));
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, forName(ctx, "kafka.value.deserializer.class", StringDeserializer.class));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "15000");
        return props;
    }
    
    // producer配置项，通常从Context里读取配置
    private static Map<String, Object> producerProps(Context ctx) {
        
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ctx.getString("kafka.bootstrap.servers"));
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, ctx.getInteger("kafka.batchSize", 16384));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, forName(ctx, "kafka.key.serializer.class", StringSerializer.class));
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, forName(ctx, "kafka.value.serializer.class", StringSerializer.class));
        props.put(ProducerConfig.ACKS_CONFIG, ctx.getInteger("kafka.acks", 1));
        props.put(ProducerConfig.RETRIES_CONFIG, 0);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        return props;
    }
    
    // 加载序列化类
    public static Class<?> forName(Context ctx, String propertyName, Class<?> clazz) {
        
        String clazzName = ctx.getString(propertyName);
        if (StringUtils.isEmpty(clazzName)) {
            return clazz;
        }
        Class<?> result = clazz;
        try {
            result = Class.forName(clazzName);
        } catch (Exception ex) {
            // ignore
        }
        return result;
    }
}
