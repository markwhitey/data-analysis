# 业务概况

------

通过实时的分析用户的流量数据，获取用户动态的充值的订单量、充值全过程的平均时长、以及某段时间充值的成功率等。首先解析数据格式，从标签体系库中截取出需要的字段值，使用 SparkStreaming 的对抽取出来的属性加以处理。
**1）统计每个市的充值失败的次数，并以地图的方式显示数据的分布情况。**

**2）以市为维度，统计订单量排名前 5 的市，并统计每个市的订单的成功率。**

**3）实时统计全省的每分钟的充值笔数和充值金额。**



<br>

# 实现要点

------

**1、采用 spark streaming 直连 kafka 方式，避免数据重复消费和丢失。**

**2、手动管理 Offset, 并对 Offset 做校验，通过获取 MySQL 中持有的偏移量与 Kafka 集群上的 EarliestOffset 进行对比，保证数据准确与安全。**

**3、实时计算的所有指标数据都存储到 redis，并使用 Echarts 做数据可视化**

<br>

# 项目QA

------

## 为什么创建数据库和表,将kafka 的offset偏移量存储下来

在从 kafka 接受流式数据的时候，spark 提供了两种方式，Dstream 和 DirectStream，在 spark2.2 中已经不在提供第一种方式，具体区别这儿就不再描述了，第二种方式 spark 是用的 kafka 低阶 api，每个 RDD 对应一个 topic 的分区，这种情况，需要借助于外部存储来管理 offset，或者简单点，自己手动利用 kafka 来管理 offset，否则在程序重启时找不到 offset 从最新的开始消费，会有丢失数据的情况。一般步骤如下：

1. 在 Direct DStream 初始化的时候，需要指定一个包含每个 topic 的每个分区的 offset 用于让 Direct DStream 从指定位置读取数据。
2. 读取并处理消息
3. 处理完之后存储结果数据
4. 最后，将 offsets 保存在外部持久化数据库如 HBase, Kafka, HDFS, and ZooKeeper 中



### 一、kafka 管理 offset

Apache Spark 2.1.x 以及 spark-streaming-kafka-0-10 使用新的的消费者 API 即异步提交 API。你可以在你确保你处理后的数据已经妥善保存之后使用 commitAsync API（异步提交 API）来向 Kafka 提交 offsets。新的消费者 API 会以消费者组 id 作为唯一标识来提交 offsets，将 offsets 提交到 Kafka 中。目前这还是实验性特性。

```scala
stream.foreachRDD { rdd =>
 
  val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
 
  // some time later, after outputs have completed
 
  stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges)
 
}
```

### 二、zookeeper 管理 offset

在初始化 kafka stream 的时候，查看 zookeeper 中是否保存有 offset，有就从该 offset 进行读取，没有就从最新 / 旧进行读取。在消费 kafka 数据的同时，将每个 partition 的 offset 保存到 zookeeper 中进行备份

```scala
val sparkConf = new SparkConf().setMaster("local[*]").setAppName("spark-streaming")
    val ssc = new StreamingContext(sparkConf, Seconds(10))
    val topic: String = "test"
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "xxx.xxx.xxx.xxx:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "spark-streaming-06",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )
    var kafkaStream: InputDStream[ConsumerRecord[String, String]] = null
    val zkClient = new ZkClient("XXX.XXX.XXX.XXX")
    var fromOffsets: Map[TopicPartition, Long] = Map()
    val children = zkClient.countChildren("offsetDir")
    if (children > 0) {
      for (i <- 0 until children) {
        val partitionOffset = zkClient.readData[String]("offsetDir" + "/" + i)
        val tp = new TopicPartition(topic, i)
        fromOffsets += (tp -> partitionOffset.toLong)
        kafkaStream = KafkaUtils.createDirectStream[String, String](
          ssc, PreferConsistent, Subscribe[String, String](Set(topic), kafkaParams, fromOffsets)
        )
      }
    } else {
      kafkaStream = KafkaUtils.createDirectStream[String, String](
        ssc, PreferConsistent, Subscribe[String, String](Set(topic), kafkaParams)
      )
    }
```

## 创建数据库的形式

```sql
mysql> create database offset_db;
Query OK, 1 row affected (0.01 sec)
mysql> use offset_db;
Database changed
mysql> create table offset_tb(
    ->       topic varchar(32), --kafka消息topic
    ->       groupid varchar(50), --消费者消费组
    ->       partitions int,     --分区
    ->       fromoffset bigint,  --start offset
    ->       untiloffset bigint,  -- end offset
    ->       primary key(topic,groupid,partitions) -- 联合主键
    ->     );
Query OK, 0 rows affected (0.06 sec)
```

> 参考链接:
>
> [**SparkStreaming**整合kafka数据零丢失最佳实践(含代码)](http://www.wendaoxueyuan.com/thy-post/detail/48aad165-7d63-49df-b421-d0a2b2e1477d#)
>
> **[SparkStreaming2.2+kafka 的偏移量管理](https://blog.csdn.net/cyony/article/details/81939540)**
>
> **[Kafka 消息偏移量](https://blog.csdn.net/wzw12315/article/details/78226958)**

## **kafka 全部数据清空与某一 topic 数据清空**

参考链接:https://www.cnblogs.com/swordfall/p/10014300.html

## kafka（java 客户端）消费者取不到消息，生产者消息也没发送成功

**解决办法**:
将 kafka/config/server.properties 文件中 advertised.listeners 改为如下属性。192.168.75.137 是我虚拟机的 IP。改完后重启，OK 了。Java 端的代码终于能通信了
advertised.listeners=PLAINTEXT://192.168.75.137:9092

advertised.listeners 上的注释是这样的：

```
#Hostname and port the broker will advertise to producers and consumers. If not set,
# it uses the value for "listeners" if configured. Otherwise, it will use the value
# returned from java.net.InetAddress.getCanonicalHostName().
```

意思就是说：hostname、port 都会广播给 producer、consumer。如果你没有配置了这个属性的话，则使用 listeners 的值，如果 listeners 的值也没有配置的话，则使用
java.net.InetAddress.getCanonicalHostName() 返回值 (这里也就是返回 localhost 了)。


