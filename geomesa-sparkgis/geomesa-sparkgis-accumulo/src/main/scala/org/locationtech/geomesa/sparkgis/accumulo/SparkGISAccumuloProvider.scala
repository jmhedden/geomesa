package org.locationtech.geomesa.sparkgis.accumulo

import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat
import org.apache.accumulo.core.client.mapreduce.lib.impl.InputConfigurator
import org.apache.accumulo.core.client.mapreduce.lib.util.ConfiguratorBase
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.security.Authorizations
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.geotools.data.{DataStoreFinder, Query, Transaction}
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloDataStoreFactory, AccumuloDataStoreParams}
import org.locationtech.geomesa.accumulo.index.EmptyPlan
import org.locationtech.geomesa.index.conf.QueryHints._
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.jobs.accumulo.AccumuloJobUtils
import org.locationtech.geomesa.jobs.mapreduce.GeoMesaAccumuloInputFormat
import org.locationtech.geomesa.sparkgis.SparkGISProvider
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

class SparkGISAccumuloProvider extends SparkGISProvider {
  override def canProcess(params: java.util.Map[String, java.io.Serializable]): Boolean =
    AccumuloDataStoreFactory.canProcess(params)

  def rdd(conf: Configuration,
          sc: SparkContext,
          dsParams: Map[String, String],
          query: Query,
          numberOfSplits: Option[Int]): RDD[SimpleFeature] = {
    rdd(conf, sc, dsParams, query, useMock = false, numberOfSplits)
  }

  def rdd(conf: Configuration,
          sc: SparkContext,
          dsParams: Map[String, String],
          query: Query,
          useMock: Boolean = false,
          numberOfSplits: Option[Int] = None): RDD[SimpleFeature] = {

    println(s"\n\n*** Setting up RDD with Filter: ${query.getFilter} *** \n\n")

    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[AccumuloDataStore]
    val username = AccumuloDataStoreParams.userParam.lookUp(dsParams).toString
    val password = new PasswordToken(AccumuloDataStoreParams.passwordParam.lookUp(dsParams).toString.getBytes)
    try {
      // get the query plan to set up the iterators, ranges, etc
      lazy val sft = ds.getSchema(query.getTypeName)
      lazy val qp = AccumuloJobUtils.getSingleQueryPlan(ds, query)

      if (ds == null || sft == null || qp.isInstanceOf[EmptyPlan]) {
        sc.emptyRDD[SimpleFeature]
      } else {
        val instance = ds.connector.getInstance().getInstanceName
        val zookeepers = ds.connector.getInstance().getZooKeepers

        val transform = query.getHints.getTransformSchema

        ConfiguratorBase.setConnectorInfo(classOf[AccumuloInputFormat], conf, username, password)
        if (useMock){
          ConfiguratorBase.setMockInstance(classOf[AccumuloInputFormat], conf, instance)
        } else {
          ConfiguratorBase.setZooKeeperInstance(classOf[AccumuloInputFormat], conf, instance, zookeepers)
        }
        InputConfigurator.setInputTableName(classOf[AccumuloInputFormat], conf, qp.table)
        InputConfigurator.setRanges(classOf[AccumuloInputFormat], conf, qp.ranges)
        qp.iterators.foreach(InputConfigurator.addIterator(classOf[AccumuloInputFormat], conf, _))

        if (qp.columnFamilies.nonEmpty) {
          val cf = qp.columnFamilies.map(cf => new org.apache.accumulo.core.util.Pair[Text, Text](cf, null))
          InputConfigurator.fetchColumns(classOf[AccumuloInputFormat], conf, cf)
        }

        if (numberOfSplits.isDefined) {
          GeoMesaConfigurator.setDesiredSplits(conf, numberOfSplits.get * sc.getExecutorStorageStatus.length)
          InputConfigurator.setAutoAdjustRanges(classOf[AccumuloInputFormat], conf, false)
          InputConfigurator.setAutoAdjustRanges(classOf[GeoMesaAccumuloInputFormat], conf, false)
        }

        org.apache.accumulo.core.client.mapreduce.lib.impl.InputConfigurator.setBatchScan(classOf[AccumuloInputFormat], conf, true)
        org.apache.accumulo.core.client.mapreduce.lib.impl.InputConfigurator.setBatchScan(classOf[GeoMesaAccumuloInputFormat], conf, true)
        GeoMesaConfigurator.setSerialization(conf)
        GeoMesaConfigurator.setTable(conf, qp.table)
        GeoMesaConfigurator.setDataStoreInParams(conf, dsParams)
        GeoMesaConfigurator.setFeatureType(conf, sft.getTypeName)

        // set the secondary filter if it exists and is  not Filter.INCLUDE
        qp.filter.secondary
          .collect { case f if f != Filter.INCLUDE => f }
          .foreach { f => GeoMesaConfigurator.setFilter(conf, ECQL.toCQL(f)) }

        transform.foreach(GeoMesaConfigurator.setTransformSchema(conf, _))

        // Configure Auths from DS
        val auths = Option(AccumuloDataStoreParams.authsParam.lookUp(dsParams).asInstanceOf[String])
        auths.foreach { a =>
          val authorizations = new Authorizations(a.split(","): _*)
          InputConfigurator.setScanAuthorizations(classOf[AccumuloInputFormat], conf, authorizations)
        }

        sc.newAPIHadoopRDD(conf, classOf[GeoMesaAccumuloInputFormat], classOf[Text], classOf[SimpleFeature]).map(U => U._2)
      }
    } finally {
      if (ds != null) {
        ds.dispose()
      }
    }
  }

  /**
    * Writes this RDD to a GeoMesa table.
    * The type must exist in the data store, and all of the features in the RDD must be of this type.
    *
    * @param rdd
    * @param writeDataStoreParams
    * @param writeTypeName
    */
  def save(rdd: RDD[SimpleFeature], writeDataStoreParams: Map[String, String], writeTypeName: String): Unit = {
    val ds = DataStoreFinder.getDataStore(writeDataStoreParams).asInstanceOf[AccumuloDataStore]
    try {
      require(ds.getSchema(writeTypeName) != null,
        "feature type must exist before calling save.  Call .createSchema on the DataStore before calling .save")
    } finally {
      ds.dispose()
    }

    rdd.foreachPartition { iter =>
      val ds = DataStoreFinder.getDataStore(writeDataStoreParams).asInstanceOf[AccumuloDataStore]
      val featureWriter = ds.getFeatureWriterAppend(writeTypeName, Transaction.AUTO_COMMIT)
      val attrNames = featureWriter.getFeatureType.getAttributeDescriptors.map(_.getLocalName)
      try {
        iter.foreach { case rawFeature =>
          val newFeature = featureWriter.next()
          attrNames.foreach(an => newFeature.setAttribute(an, rawFeature.getAttribute(an)))
          featureWriter.write()
        }
      } finally {
        featureWriter.close()
        ds.dispose()
      }
    }
  }

}