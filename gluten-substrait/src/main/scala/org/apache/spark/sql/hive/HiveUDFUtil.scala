/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.hive

import org.apache.gluten.exception.GlutenNotSupportException
import org.apache.spark.sql.catalyst.expressions.Expression

object HiveUDFUtil {
  def isHiveUDF(expr: Expression): Boolean = expr match {
    case _: HiveSimpleUDF => true
    case _: HiveGenericUDF => true
    case _: HiveGenericUDTF => true
    case _ => false
  }

  def isHiveUDAF(expr: Expression): Boolean = expr match {
    case _: HiveUDAFFunction => true
    case _ => false
  }

  def extractUDFNameAndClassName(expr: Expression): (String, String) = expr match {
    case s: HiveSimpleUDF =>
      (s.name.stripPrefix("default."), s.funcWrapper.functionClassName)
    case g: HiveGenericUDF =>
      (g.name.stripPrefix("default."), g.funcWrapper.functionClassName)
    case g: HiveGenericUDF =>
      (g.name.stripPrefix("default."), g.funcWrapper.functionClassName)
    case _ =>
      throw new GlutenNotSupportException(
        s"Expression $expr is not a HiveSimpleUDF or HiveGenericUDF")
  }
}

