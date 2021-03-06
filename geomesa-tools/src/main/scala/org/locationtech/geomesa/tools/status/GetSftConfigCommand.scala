/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.tools.status

import java.util

import com.beust.jcommander._
import org.geotools.data.DataStore
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.tools.status.GetSftConfigCommand.{Spec, TypeSafe}
import org.locationtech.geomesa.tools.{DataStoreCommand, RequiredTypeNameParam}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeatureType

import scala.util.control.NonFatal

trait GetSftConfigCommand[DS <: DataStore] extends DataStoreCommand[DS] {

  override val name: String = "get-sft-config"

  override def params: GetSftConfigParams

  override def execute() = {
    import scala.collection.JavaConversions._

    logger.info(s"Getting SFT for type name '${params.featureName}'")
    try {
      val sft = withDataStore(getSchema)
      if (sft == null) {
        throw new ParameterException(s"Schema '${params.featureName}' does not exist in the provided datastore")
      }
      params.format.map(_.toLowerCase).foreach {
        case TypeSafe => println(SimpleFeatureTypes.toConfigString(sft, !params.excludeUserData, params.concise))
        case Spec => println(SimpleFeatureTypes.encodeType(sft, !params.excludeUserData))
        // shouldn't happen due to parameter validation
        case _ => throw new RuntimeException("Unhandled format")
      }
    } catch {
      case p: ParameterException => throw p
      case NonFatal(e) => logger.error(s"Error describing feature '${params.featureName}':", e)
    }
  }

  def getSchema(ds: DS): SimpleFeatureType = ds.getSchema(params.featureName)

}

object GetSftConfigCommand {
  val Spec = "spec"
  val TypeSafe = "typesafe"
}

// @Parameters(commandDescription = "Get the SimpleFeatureType of a feature")
trait GetSftConfigParams extends RequiredTypeNameParam {
  @Parameter(names = Array("--concise"), description = "Render in concise format", required = false)
  var concise: Boolean = false

  @Parameter(names = Array("--format"), description = "Output formats (allowed values are typesafe, spec)", required = false, validateValueWith = classOf[FormatValidator])
  var format: java.util.List[String] = {
    val list = new java.util.ArrayList[String]()
    list.add("typesafe")
    list
  }

  @Parameter(names = Array("--exclude-user-data"), description = "Exclude user data", required = false)
  var excludeUserData: Boolean = false
}

class FormatValidator extends IValueValidator[java.util.List[String]] {
  override def validate(name: String, value: util.List[String]): Unit = {
    import scala.collection.JavaConversions._
    if (value == null || value.isEmpty || value.map(_.toLowerCase ).exists(v => v != Spec && v != TypeSafe)) {
      throw new ParameterException(s"Invalid value for format: ${Option(value).map(_.mkString(",")).orNull}")
    }
  }
}
