package Utils
import com.alibaba.fastjson.JSON
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.rdd.RDD
import redis.clients.jedis.JedisPool
object Jpools {
  private val poolConfig = new GenericObjectPoolConfig()
  poolConfig.setMaxIdle(5)      //最大的空闲连接数，连接池中最大的空闲连接数，默认是8
  poolConfig.setMaxTotal(2000)  //只支持最大的连接数，连接池中最大的连接数，默认是8

  //连接池是私有的不能对外公开访问
  private lazy val jedisPool = new JedisPool(poolConfig, AppParams.redisHost)

  def getJedis={
    val jedis = jedisPool.getResource
    jedis.select(AppParams.selectDBIndex)
    jedis
  }
}
