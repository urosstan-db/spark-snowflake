package com.snowflakedb.spark.snowflakedb

import java.io.File
import java.net.URI

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.Rule
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.s3native.S3NInMemoryFileSystem
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite}

/**
  * Created by mzukowski on 8/9/16.
  */

private class TestContext extends SparkContext("local", "SnowflakeBaseTest")
{
  /**
    * A text file containing fake unloaded Snowflake data of all supported types
    */
  val testData = new File("src/test/resources/snowflake_unload_data.txt").toURI.toString

  override def newAPIHadoopFile[K, V, F <: InputFormat[K, V]](
                                                               path: String,
                                                               fClass: Class[F],
                                                               kClass: Class[K],
                                                               vClass: Class[V],
                                                               conf: Configuration = hadoopConfiguration): RDD[(K, V)] = {
    super.newAPIHadoopFile[K, V, F](testData, fClass, kClass, vClass, conf)
  }
}

class BaseTest extends FunSuite
    with QueryTest
    with BeforeAndAfterAll
    with BeforeAndAfterEach
{

  /**
    * Spark Context with Hadoop file overridden to point at our local test data file for this suite,
    * no matter what temp directory was generated and requested.
    */
  protected var sc: SparkContext = _

  protected var testSqlContext: SQLContext = _

  protected var mockS3Client: AmazonS3Client = _

  protected var expectedDataDF: DataFrame = _

  protected var s3FileSystem: FileSystem = _

  protected val s3TempDir: String = "s3n://test-bucket/temp-dir/"

  // Parameters common to most tests. Some parameters are overridden in specific tests.
  protected def defaultParams: Map[String, String] = Map(
    "tempdir" -> s3TempDir,
    "dbtable" -> "test_table",
    "sfurl" -> "account.snowflakecomputing.com:443",
    "sfuser" -> "username",
    "sfpassword" -> "password"
  )

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Always run in UTC
    System.setProperty("user.timezone", "GMT")

    sc = new TestContext
    sc.hadoopConfiguration.set("fs.s3n.impl", classOf[S3NInMemoryFileSystem].getName)
    // We need to use a DirectOutputCommitter to work around an issue which occurs with renames
    // while using the mocked S3 filesystem.
    sc.hadoopConfiguration.set("spark.sql.sources.outputCommitterClass",
      classOf[DirectOutputCommitter].getName)
    sc.hadoopConfiguration.set("mapred.output.committer.class",
      classOf[DirectOutputCommitter].getName)
    sc.hadoopConfiguration.set("fs.s3.awsAccessKeyId", "test1")
    sc.hadoopConfiguration.set("fs.s3.awsSecretAccessKey", "test2")
    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "test1")
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "test2")
    // Configure a mock S3 client so that we don't hit errors when trying to access AWS in tests.
    mockS3Client = Mockito.mock(classOf[AmazonS3Client], Mockito.RETURNS_SMART_NULLS)
    when(mockS3Client.getBucketLifecycleConfiguration(anyString())).thenReturn(
      new BucketLifecycleConfiguration().withRules(
        new Rule().withPrefix("").withStatus(BucketLifecycleConfiguration.ENABLED)
      ))
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    s3FileSystem = FileSystem.get(new URI(s3TempDir), sc.hadoopConfiguration)
    testSqlContext = new SQLContext(sc)
    expectedDataDF =
      testSqlContext.createDataFrame(sc.parallelize(TestUtils.expectedData), TestUtils.testSchema)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    testSqlContext = null
    expectedDataDF = null
    s3FileSystem = null
    FileSystem.closeAll()
  }

  override def afterAll(): Unit = {
    sc.stop()
    super.afterAll()
  }

}
