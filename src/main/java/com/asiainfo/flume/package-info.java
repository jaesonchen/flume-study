/**   
 * flume:
Flume是Apache提供的高可用，分布式日志收集、传输系统
og：属于Flume0.9版本之前
ng：属于Flume1.0版本之前
og是单线程，主从结构，原本由zookeeper管理
ng是双线程，已经取消master管理机制和zookeeper管理机制，变成了纯粹的传输工具

Flume的优势
1. Flume可以将应用产生的数据存储到任何集中存储器中，比如HDFS,HBase,Kafka
2. 当收集数据的速度超过将写入数据的时候，也就是当收集信息遇到峰值时，这时候收集的信息非常大，
甚至超过了系统的写入数据能力，这时候，Flume会在数据生产者和数据收容器间做出调整，保证其能够在两者之间提供一共平稳的数据.
3. 提供上下文路由，自动选择event流入的channel、sink
4. Flume的管道是基于事务，保证了数据在传送和接收时的一致性.
5. Flume是可靠的，容错性高的，可升级的，易管理的,并且可定制的。?
6. 支持各种接入资源数据的类型以及接出数据类型
7. 支持多路径流量，多管道接入流量，多管道接出流量，上下文路由等
8. 可以被水平扩展，只需要在新的数据节点架设agent即可水平扩展


Flume Agent
Flume内部有一个或者多个Agent,然而对于每一个Agent来说,它就是一共独立的守护进程(JVM),它从客户端哪儿接收数据,
或者从其他的 Agent哪儿接收,然后迅速的将获取的数据传给下一个目的节点sink,或者agent. 

Flume Agent
Agent由source, channel, sink三个组件组成.

Flume Source:
从数据发生器接收数据,并将接收的数据以Flume的event格式传递给一个或者多个通道channal。
Flume source 组件可以处理各种类型、各种格式的日志数据，包括 avro、thrift、exec、jms、spooling directory、netcat、sequence generator、syslog、http、legacy.

Flume Channel:
channal是一种短暂的存储容器,它将从source处接收到的event格式的数据缓存起来,直到它们被sinks消费掉,它在source和sink间起着一共桥梁的作用,
channal是一个完整的事务,这一点保证了数据在收发的时候的一致性. 并且它可以和任意数量的source和sink链接. 
只有在sink将channel中的数据成功发送出去之后，channel才会将临时数据进行删除，这种机制保证了数据传输的可靠性与安全性。
Flume channel 使用被动存储机制. 它存储的数据的写入是靠 Flume source 来完成的, 数据的读取是靠后面的组件 Flume sink 来完成的.
支持的类型有: JDBC channel , File System channel , Memory channel等.
Memory Channel在不需要关心数据丢失的情景下适用。
File Channel将所有事件写到磁盘，因此在程序关闭或机器宕机的情况下不会丢失数据。

Flume sink:
sink将数据存储到集中存储器比如Hbase和HDFS,它从channals消费数据(events)并将其传递给目标地. 目标地可能是另一个sink,也可能HDFS,HBase.
Sink 不断地轮询 Channel 中的事件且批量地移除它们，并将这些事件批量写入到存储或索引系统、或者发送到另一个Flume Agent。
Sink 是完全事务性的。
在从 Channel 批量删除数据之前，每个 Sink 用 Channel 启动一个事务。批量事件一旦成功写出到存储系统或下一个Flume Agent，Sink 就利用 Channel 提交事务。
事务一旦被提交，该 Channel 从自己的内部缓冲区删除事件。如果写入失败，将缓冲区takeList中的数据归还给Channel。
Sink组件目的地包括hdfs、logger、avro、thrift、ipc、file、null、HBase、solr、自定义。

  
Flume 事件
事件作为Flume内部数据传输的最基本单元.它是由一个承载数据的字节数组(该数据组是从数据源接入点传入)和一个可选头部构成.
我们在将event做私人定制插件时比如：flume-hbase-sink插件时，获取的就是event然后对其解析，并依据情况做过滤等，然后在传输给HBase或者HDFS.

Flume插件
Interceptors拦截器：用于source和channel之间,用来更改或者检查Flume的events数据
channels Selectors管道选择器：在多管道是被用来选择使用那一条管道来传递数据(events).
管道选择器又分为如下两种:
a. 默认管道选择器: 每一个管道传递的都是相同的events
b. 多路复用通道选择器: 依据每一个event的头部header的属性值选择管道.
sink processor：用于激活被选择的sinks群中特定的sink,用于负载均衡.


Flume与Kafka对比
1. kafka和flume都是日志系统，kafka是分布式消息中间件，自带存储，提供push和pull存取数据功能。
flume分为agent（数据采集器）,collector（数据简单处理和写入）,storage（存储器）三部分，每一部分都是可以定制的。
比如agent采用RPC（Thrift-RPC）、text（文件）等，storage指定用hdfs做。
2. kafka做日志缓存应该是更为合适的，但是 flume的数据采集部分做的很好，可以定制很多数据源，减少开发量。
所以比较流行flume+kafka模式，如果为了利用flume写hdfs的能力，也可以采用kafka+flume的方式。
3. Kafka?一个可持久化的分布式的消息队列。你可以有许多生产者和很多的消费者共享多个主题Topics。
相比之下,Flume是一个专用工具被设计为旨在往HDFS,HBase发送数据。它对HDFS有特殊的优化，并且集成了Hadoop的安全特性。
所以，Cloudera?建议如果数据被多个系统消费的话，使用kafka；如果数据被设计给Hadoop使用，使用Flume。
4. Flume内置很多的source和sink组件。使用Kafka意味着你准备好了编写你自己的生产者和消费者代码。
如果已经存在的Flume Sources和Sinks满足你的需求，并且你更喜欢不需要任何开发的系统，请使用Flume。
5. Flume可以使用拦截器实时处理数据。这些对数据屏蔽或者过量是很有用的。Kafka需要外部的流处理系统才能做到。
6. Kafka和Flume都是可靠的系统,通过适当的配置能保证零数据丢失。然而，Flume不支持副本事件。
于是，如果Flume代理的一个节点崩溃了，即使使用了可靠的文件管道方式，你也将丢失这些事件直到你恢复这些磁盘。
如果你需要一个高可靠行的管道，那么使用Kafka是个更好的选择。
7. Flume和Kafka可以很好地结合起来使用。如果你的设计需要从Kafka到Hadoop的流数据，
使用Flume代理并配置Kafka的Source读取数据也是可行的：你没有必要实现自己的消费者。你可以直接利用Flume与HDFS及HBase的结合的所有好处。


Flume+Kafka+Storm+Redis实时分析系统基本架构：
1) 先由电商系统的订单服务器产生指定格式订单日志
2) 然后使用Flume去监听订单日志，并实时把每一条日志信息抓取下来并存进Kafka消息系统中
3) 接着由Storm系统消费Kafka中的消息
4) 接下来就是使用用户定义好的Storm Topology去进行日志信息的分析并输出到Redis缓存数据库中(也可以进行持久化)
5) 最后用Web APP去读取Redis中分析后的订单信息并展示给用户。
 * 
 * @author chenzq  
 * @date 2019年5月12日 下午1:12:06
 * @version V1.0
 * @Copyright: Copyright(c) 2019 jaesonchen.com Inc. All rights reserved. 
 */
package com.asiainfo.flume;