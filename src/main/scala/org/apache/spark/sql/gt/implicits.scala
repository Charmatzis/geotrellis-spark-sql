/*
 * Copyright 2017 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.gt

import geotrellis.proj4.CRS
import geotrellis.raster.histogram.Histogram
import geotrellis.raster.summary.Statistics
import geotrellis.raster.{MultibandTile, Tile}
import geotrellis.spark.TemporalProjectedExtent
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

trait implicits {
  implicit def singlebandTileEncoder: Encoder[Tile] = ExpressionEncoder()
  implicit def multibandTileEncoder: Encoder[MultibandTile] = ExpressionEncoder()
  implicit def crsEncoder: Encoder[CRS] = ExpressionEncoder()
  implicit def extentEncoder: Encoder[Extent] = ExpressionEncoder()
  implicit def projectedExtentEncoder: Encoder[ProjectedExtent] = ExpressionEncoder()
  implicit def temporalProjectedExtentEncoder: Encoder[TemporalProjectedExtent] = ExpressionEncoder()
  implicit def histogramDoubleEncoder: Encoder[Histogram[Double]] = ExpressionEncoder()
  implicit def histogramIntEncoder: Encoder[Histogram[Int]] = ExpressionEncoder()
  implicit def histogramStatsEncoder: Encoder[Statistics[Double]] = ExpressionEncoder()
}
object implicits extends implicits
