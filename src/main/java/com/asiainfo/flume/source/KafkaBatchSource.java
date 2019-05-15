package com.asiainfo.flume.source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.AbstractPollableSource;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asiainfo.flume.util.KafkaUtils;

import kafka.cluster.Broker;
import kafka.cluster.BrokerEndPoint;
import kafka.zk.KafkaZkClient;
import scala.Option;
import scala.collection.JavaConverters;

/**   
 * @Description: Kafka batch source 实现
 * 
 * @author chenzq  
 * @date 2019年4月21日 下午8:49:20
 * @version V1.0
 * @Copyright: Copyright(c) 2019 jaesonchen.com Inc. All rights reserved. 
 */
public class KafkaBatchSource extends AbstractPollableSource implements Configurable {

    private static final int ZK_SESSION_TIMEOUT = 30000;
    private static final int ZK_CONNECTION_TIMEOUT = 30000;
    
    private Properties kafkaProps;
    private KafkaConsumer<String, byte[]> consumer;
    private Iterator<ConsumerRecord<String, byte[]>> it;
    private Subscriber<?> subscriber;
    private Map<TopicPartition, OffsetAndMetadata> tpAndOffsetMetadata;
    private Map<String, String> headers;
    private final List<Event> eventList = new ArrayList<Event>();
    private AtomicBoolean rebalanceFlag;
    private int batchSize;
    private int batchDurationMillis;
    private String zookeeperConnect;
    private String bootstrapServers;
    private String groupId;
    
    /* 
     * @see org.apache.flume.source.AbstractPollableSource#doProcess()
     */
    @Override
    protected Status doProcess() throws EventDeliveryException {
        
        final String batchUUID = UUID.randomUUID().toString();
        String kafkaKey;
        Event event;
        byte[] eventBody;

        try {
            // prepare time variables for new batch
            final long maxBatchEndTime = System.currentTimeMillis() + this.batchDurationMillis;

            while (eventList.size() < this.batchSize && System.currentTimeMillis() < maxBatchEndTime) {
              
                if (it == null || !it.hasNext()) {
                    // Obtaining new records
                    // Poll time is remainder time for current batch.
                    long durMs = Math.max(0L, maxBatchEndTime - System.currentTimeMillis());
                    Duration duration = Duration.ofMillis(durMs);
                    ConsumerRecords<String, byte[]> records = consumer.poll(duration);
                    it = records.iterator();
                    // check records after poll
                    if (!it.hasNext()) {
                        // batch time exceeded
                        break;
                    }
                  
                    // this flag is set to true in a callback when some partitions are revoked.
                    // If there are any records we commit them.
                    if (rebalanceFlag.compareAndSet(true, false)) {
                        break;
                    }
                }

                // get next message
                ConsumerRecord<String, byte[]> message = it.next();
                kafkaKey = message.key();
                eventBody = message.value();
              
                headers.clear();
                headers = new HashMap<String, String>(5);
                // Add headers to event (timestamp, topic, partition, offset, key)
                headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
                headers.put("topic", message.topic());
                headers.put("partition", String.valueOf(message.partition()));
                headers.put("offset", String.valueOf(message.offset()));
                headers.put("key", kafkaKey);

                event = EventBuilder.withBody(eventBody, headers);
                eventList.add(event);

                // For each partition store next offset that is going to be read.
                tpAndOffsetMetadata.put(new TopicPartition(message.topic(), message.partition()),
                        new OffsetAndMetadata(message.offset() + 1, batchUUID));
            }

            if (eventList.size() > 0) {
                getChannelProcessor().processEventBatch(eventList);
                eventList.clear();

                // commit offset
                if (!tpAndOffsetMetadata.isEmpty()) {
                    consumer.commitSync(tpAndOffsetMetadata);
                    tpAndOffsetMetadata.clear();
                }
                return Status.READY;
            }
        } catch (Exception e) {
            // ignore
        }
        return Status.BACKOFF;
    }

    /* 
     * @see org.apache.flume.source.BasicSourceSemantics#doConfigure(org.apache.flume.Context)
     */
    @Override
    protected void doConfigure(Context context) throws FlumeException {
        
        this.tpAndOffsetMetadata = new HashMap<TopicPartition, OffsetAndMetadata>();
        this.rebalanceFlag = new AtomicBoolean(false);
        this.kafkaProps = new Properties();
        
        // group.id
        this.groupId = context.getString("kafka.group.id", "flume");
        
        // topics
        String topicProperty = context.getString("kafka.topics");
        if (StringUtils.isNotEmpty(topicProperty)) {
            this.subscriber = new TopicListSubscriber(topicProperty);
        } else if (StringUtils.isNotEmpty(topicProperty = context.getString("kafka.topics.regex"))) {
            this.subscriber = new PatternSubscriber(topicProperty);
        } else {
            throw new ConfigurationException("At least one Kafka topic must be specified.");
        }
        
        // batchSize
        this.batchSize = context.getInteger("kafka.batchSize", 1000);
        this.batchDurationMillis = context.getInteger("kafka.batchDurationMillis", 1000);
        
        // zookeeper.connect
        this.zookeeperConnect = context.getString("kafka.zookeeper.connect");
        
        // bootstrap.servers，没有配置时从zookeeper读取
        this.bootstrapServers = context.getString("kafka.bootstrap.servers");
        if (StringUtils.isEmpty(bootstrapServers)) {
            if (StringUtils.isEmpty(zookeeperConnect)) {
                throw new ConfigurationException("Bootstrap Servers must be specified");
            } else {
                this.bootstrapServers = lookupBootstrap(zookeeperConnect, 
                        SecurityProtocol.valueOf(CommonClientConfigs.DEFAULT_SECURITY_PROTOCOL));
            }
        }
        
        kafkaProps.putAll(context.getSubProperties("kafka.consumer"));
        kafkaProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        kafkaProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
                KafkaUtils.forName(context, "kafka.key.deserializer.class", StringDeserializer.class));
        kafkaProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                KafkaUtils.forName(context, "kafka.value.deserializer.class", StringDeserializer.class));
        kafkaProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        if (StringUtils.isNotEmpty(groupId)) {
            kafkaProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }
    }

    /* 
     * @see org.apache.flume.source.BasicSourceSemantics#doStart()
     */
    @Override
    protected void doStart() throws FlumeException {
        
        // 配置了zookeeperConnect并且只有一个topic时，可以migrate offset
        if (StringUtils.isNotEmpty(this.zookeeperConnect)) {
            if (subscriber instanceof TopicListSubscriber 
                    && ((TopicListSubscriber) subscriber).get().size() == 1) {
                migrateOffsets(((TopicListSubscriber) subscriber).get().get(0));
            }
        }
        // initialize a consumer.
        consumer = new KafkaConsumer<String, byte[]>(kafkaProps);
        // Subscribe for topics by already specified strategy
        subscriber.subscribe(consumer, new SourceRebalanceListener(rebalanceFlag));
    }

    /* 
     * @see org.apache.flume.source.BasicSourceSemantics#doStop()
     */
    @Override
    protected void doStop() throws FlumeException {
        if (consumer != null) {
            consumer.wakeup();
            consumer.close();
        }
    }
    
    // 从zookeeper中migrate topic offset到broker中
    protected void migrateOffsets(String topic) {
        
        try (KafkaZkClient zkClient = KafkaZkClient.apply(zookeeperConnect, 
                JaasUtils.isZkSecurityEnabled(), ZK_SESSION_TIMEOUT, ZK_CONNECTION_TIMEOUT, 10,
                Time.SYSTEM, "kafka.server", "SessionExpireListener");
                KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(kafkaProps)) {
            
            // 读取broker中的consumer offset
            Map<TopicPartition, OffsetAndMetadata> kafkaOffsets = getKafkaOffsets(consumer, topic);
            if (kafkaOffsets == null || !kafkaOffsets.isEmpty()) {
                return;
            }
            // No Kafka offsets found. Migrating zookeeper offsets
            Map<TopicPartition, OffsetAndMetadata> zookeeperOffsets = getZookeeperOffsets(zkClient, consumer, topic);
            if (zookeeperOffsets.isEmpty()) {
                return;
            }
            // Committing Zookeeper offsets to Kafka
            consumer.commitSync(zookeeperOffsets);
        }
    }
    
    // 从zookeeper中读取Bootstrapserver
    protected String lookupBootstrap(String zookeeperConnect, SecurityProtocol securityProtocol) {
        
        try (KafkaZkClient zkClient = KafkaZkClient.apply(zookeeperConnect,
                JaasUtils.isZkSecurityEnabled(), ZK_SESSION_TIMEOUT, ZK_CONNECTION_TIMEOUT, 10,
                Time.SYSTEM, "kafka.server", "SessionExpireListener")) {
            
            List<Broker> brokerList = JavaConverters.seqAsJavaListConverter(zkClient.getAllBrokersInCluster()).asJava();
            List<BrokerEndPoint> endPoints = brokerList.stream()
                    .map(broker -> broker.brokerEndPoint(ListenerName.forSecurityProtocol(securityProtocol)))
                    .collect(Collectors.toList());
            List<String> connections = new ArrayList<>();
            for (BrokerEndPoint endPoint : endPoints) {
                connections.add(endPoint.connectionString());
            }
            return StringUtils.join(connections, ',');
        }
    }
    
    // 从consumer中读取topic所有分区上一次commit的offset
    protected Map<TopicPartition, OffsetAndMetadata> getKafkaOffsets(
            KafkaConsumer<String, byte[]> consumer, String topic) {
        
        Map<TopicPartition, OffsetAndMetadata> offsets = null;
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        if (partitions != null) {
            offsets = new HashMap<>();
            for (PartitionInfo partition : partitions) {
                TopicPartition tp = new TopicPartition(topic, partition.partition());
                OffsetAndMetadata offsetAndMetadata = consumer.committed(tp);
                if (offsetAndMetadata != null) {
                    offsets.put(tp, offsetAndMetadata);
                }
            }
        }
        return offsets;
    }
    
    // 从zookeeper读取topic 各分区的当前offset
    protected Map<TopicPartition, OffsetAndMetadata> getZookeeperOffsets(
            KafkaZkClient zkClient, KafkaConsumer<String, byte[]> consumer, String topic) {

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        for (PartitionInfo partition : partitions) {
            TopicPartition topicPartition = new TopicPartition(topic, partition.partition());
            Option<Object> optionOffset = zkClient.getConsumerOffset(groupId, topicPartition);
            if (optionOffset.nonEmpty()) {
                Long offset = (Long) optionOffset.get();
                OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(offset);
                offsets.put(topicPartition, offsetAndMetadata);
           }
        }
        return offsets;
    }
    
    // rebalance Listener
    static class SourceRebalanceListener implements ConsumerRebalanceListener {
        
        final Logger log = LoggerFactory.getLogger(getClass());
        private AtomicBoolean rebalanceFlag;

        public SourceRebalanceListener(AtomicBoolean rebalanceFlag) {
            this.rebalanceFlag = rebalanceFlag;
        }

        // Set a flag that a rebalance has occurred. Then commit already read events to kafka.
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            for (TopicPartition partition : partitions) {
                log.info("topic {} - partition {} revoked.", partition.topic(), partition.partition());
            }
            rebalanceFlag.set(true);
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            for (TopicPartition partition : partitions) {
                log.info("topic {} - partition {} assigned.", partition.topic(), partition.partition());
            }
        }
    }
    
    interface Subscriber<T> {
        void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener);
        public T get();
    }
    // 订阅topic，逗号分隔
    static class TopicListSubscriber implements Subscriber<List<String>> {
        private List<String> list;

        public TopicListSubscriber(String commaSeparatedTopics) {
            this.list = Arrays.asList(commaSeparatedTopics.split("^\\s+|\\s*,\\s*|\\s+$"));
        }

        @Override
        public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
            consumer.subscribe(list, listener);
        }

        @Override
        public List<String> get() {
          return list;
        }
    }
    // 订阅topic，正则表达式
    static class PatternSubscriber implements Subscriber<Pattern> {
        private Pattern pattern;

        public PatternSubscriber(String regex) {
            this.pattern = Pattern.compile(regex);
        }

        @Override
        public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
            consumer.subscribe(pattern, listener);
        }

        @Override
        public Pattern get() {
            return pattern;
        }
    }
}
