/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.tools.stats

import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.tools.DataStoreCommand
import org.opengis.filter.Filter

import scala.util.control.NonFatal

trait StatsCountCommand[DS <: GeoMesaDataStore[_, _, _]] extends DataStoreCommand[DS] {

  override val name = "stats-count"
  override def params: StatsCountParams

  override def execute(): Unit = {
    try { withDataStore(count) } catch {
      case NonFatal(e) => logger.error("Error analyzing stats: ", e)
    }
  }

  protected def count(ds: DS): Unit = {
    val sft = ds.getSchema(params.featureName)
    val filter = Option(params.cqlFilter).map(ECQL.toFilter).getOrElse(Filter.INCLUDE)

    if (params.exact) {
      logger.info("Running stat query...")
    }

    val count = ds.stats.getCount(sft, filter, params.exact).map(_.toString).getOrElse("Unknown")

    val label = if (params.exact) "Count" else "Estimated count"
    println(s"$label: $count")
  }
}

// @Parameters(commandDescription = "Estimate or calculate feature counts in a GeoMesa feature type")
trait StatsCountParams extends StatsParams
