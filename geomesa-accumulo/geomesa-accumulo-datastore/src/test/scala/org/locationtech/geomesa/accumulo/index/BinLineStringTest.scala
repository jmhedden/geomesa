/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import java.util.Date

import org.geotools.data.{Query, Transaction}
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithDataStore
import org.locationtech.geomesa.accumulo.index.z2.Z2Index
import org.locationtech.geomesa.accumulo.index.z3.Z3Index
import org.locationtech.geomesa.accumulo.iterators.BinAggregatingIterator
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.filter.function.{Convert2ViewerFunction, EncodedValues, ExtendedValues}
import org.locationtech.geomesa.index.conf.QueryHints._
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BinLineStringTest extends Specification with TestWithDataStore {

  import org.locationtech.geomesa.utils.geotools.GeoToolsDateFormat

  sequential

  override val spec = "name:String,track:String,dtgList:List[Date],dtg:Date,*geom:LineString:srid=4326"

  val features =
    (0 until 10).map { i =>
      val sf = new ScalaSimpleFeature(s"$i", sft)
      val geom = s"LINESTRING(40 6$i, 40.1 6$i, 40.2 6$i, 40.3 6$i)"
      val dates = new java.util.ArrayList[Date]
      (0 to 4).map(mm => GeoToolsDateFormat.parseDateTime(s"2010-05-07T0$i:0$mm:00.000Z").toDate).foreach(dates.add)
      sf.setAttributes(Array[AnyRef](s"name$i", "track1", dates, s"2010-05-07T0$i:00:00.000Z", geom))
      sf
    } ++ (10 until 20).map { i =>
      val sf = new ScalaSimpleFeature(s"$i", sft)
      val geom = s"LINESTRING(40 8${i - 10}, 40.1 8${i - 10}, 40.2 8${i - 10}, 40.3 8${i - 10})"
      val dates = new java.util.ArrayList[Date]
      (0 to 4).map(mm => GeoToolsDateFormat.parseDateTime(s"2010-05-07T0${i-10}:0$mm:00.000Z").toDate).foreach(dates.add)
      sf.setAttributes(Array[AnyRef](s"name$i", "track2", dates, s"2010-05-07T0${i-10}:00:00.000Z", geom))
      sf
    }

  addFeatures(features)

  def getQuery(filter: String, dtg: Option[String] = None, label: Option[String] = None): Query = {
    val query = new Query(sftName, ECQL.toFilter(filter))
    query.getHints.put(BIN_TRACK, "track")
    query.getHints.put(BIN_BATCH_SIZE, 100)
    dtg.foreach(query.getHints.put(BIN_DTG, _))
    label.foreach(query.getHints.put(BIN_LABEL, _))
    query
  }

  def runQuery(query: Query): Seq[EncodedValues] = {
    import BinAggregatingIterator.BIN_ATTRIBUTE_INDEX
    val binSize = if (query.getHints.containsKey(BIN_LABEL)) 24 else 16
    val features = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
    val bytes = features.map { f =>
      val array = f.getAttribute(BIN_ATTRIBUTE_INDEX).asInstanceOf[Array[Byte]]
      val copy = Array.ofDim[Byte](array.length)
      System.arraycopy(array, 0, copy, 0, array.length)
      copy
    }
    bytes.flatMap(b => b.grouped(binSize).map(Convert2ViewerFunction.decode)).toSeq
  }

  "BinAggregatingIterator" should {

    "return all points of a linestring with z2 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72)"
      val query = getQuery(filter)
      forall(ds.getQueryPlan(query))(_.table must endWith(Z2Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        bins.map(_.dtg) must contain(features(i).getAttribute("dtg").asInstanceOf[Date].getTime).exactly(4.times)
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring with z3 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72) " +
          "AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val query = getQuery(filter)
      forall(ds.getQueryPlan(query))(_.table must endWith(Z3Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        bins.map(_.dtg) must contain(features(i).getAttribute("dtg").asInstanceOf[Date].getTime).exactly(4.times)
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring plus label with z2 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72)"
      val query = getQuery(filter, label = Some("name"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z2Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins)(_ must beAnInstanceOf[ExtendedValues])
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        bins.map(_.dtg) must contain(features(i).getAttribute("dtg").asInstanceOf[Date].getTime).exactly(4.times)
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
        bins.map(_.asInstanceOf[ExtendedValues].label) must contain(Convert2ViewerFunction.convertToLabel(s"name$i")).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring plus label with z3 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72) " +
          "AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val query = getQuery(filter, label = Some("name"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z3Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins)(_ must beAnInstanceOf[ExtendedValues])
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        bins.map(_.dtg) must contain(features(i).getAttribute("dtg").asInstanceOf[Date].getTime).exactly(4.times)
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
        bins.map(_.asInstanceOf[ExtendedValues].label) must contain(Convert2ViewerFunction.convertToLabel(s"name$i")).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring and date list with z2 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72)"
      val query = getQuery(filter, dtg = Some("dtgList"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z2Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        val baseDate = features(i).getAttribute("dtg").asInstanceOf[Date].getTime
        bins.map(_.dtg) must containAllOf(Seq(baseDate, baseDate + 60000, baseDate + 120000, baseDate + 180000))
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring and date list with z3 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72) " +
          "AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val query = getQuery(filter, dtg = Some("dtgList"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z3Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        val baseDate = features(i).getAttribute("dtg").asInstanceOf[Date].getTime
        bins.map(_.dtg) must containAllOf(Seq(baseDate, baseDate + 60000, baseDate + 120000, baseDate + 180000))
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring and date list plus label with z2 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72)"
      val query = getQuery(filter, dtg = Some("dtgList"), label = Some("name"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z2Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins)(_ must beAnInstanceOf[ExtendedValues])
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        val baseDate = features(i).getAttribute("dtg").asInstanceOf[Date].getTime
        bins.map(_.dtg) must containAllOf(Seq(baseDate, baseDate + 60000, baseDate + 120000, baseDate + 180000))
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
        bins.map(_.asInstanceOf[ExtendedValues].label) must contain(Convert2ViewerFunction.convertToLabel(s"name$i")).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }

    "return all points of a linestring and date list plus label with z3 index" >> {
      val filter = "bbox(geom, 38, 58, 42, 72) " +
          "AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val query = getQuery(filter, dtg = Some("dtgList"), label = Some("name"))
      forall(ds.getQueryPlan(query))(_.table must endWith(Z3Index.name))

      val bins = runQuery(query)

      bins must haveLength(40)
      forall(bins)(_ must beAnInstanceOf[ExtendedValues])
      forall(bins.map(_.trackId))(_ mustEqual "track1".hashCode.toString)
      forall(0 until 10) { i =>
        val baseDate = features(i).getAttribute("dtg").asInstanceOf[Date].getTime
        bins.map(_.dtg) must containAllOf(Seq(baseDate, baseDate + 60000, baseDate + 120000, baseDate + 180000))
        bins.map(_.lat) must contain(60.0f + i).exactly(4.times)
        bins.map(_.asInstanceOf[ExtendedValues].label) must contain(Convert2ViewerFunction.convertToLabel(s"name$i")).exactly(4.times)
      }
      bins.map(_.lon) must contain(40.0f).exactly(10.times)
      bins.map(_.lon) must contain(40.1f).exactly(10.times)
      bins.map(_.lon) must contain(40.2f).exactly(10.times)
      bins.map(_.lon) must contain(40.3f).exactly(10.times)
    }
  }
}
