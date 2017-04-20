/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright (c) 2017. Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.spark.sql.gt

import java.nio.file.Files
import java.sql.Timestamp

import geotrellis.raster.histogram.Histogram
import geotrellis.raster.mapalgebra.local.{Max, Min}
import geotrellis.raster.{ByteCellType, MultibandTile, Tile, TileFeature}
import geotrellis.spark.TemporalProjectedExtent
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.spark.sql.gt.functions._
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, SaveMode}
import org.scalatest.{FunSpec, Inspectors, Matchers}
import org.apache.spark.sql.functions._
//import org.apache.spark.sql.execution.debug._

/**
 * Test rig for Spark UDTs and friends for GT.
 * @author sfitch
 * @since 3/30/17
 */
class GTSQLSpec extends FunSpec with Matchers with Inspectors with TestEnvironment with TestData {

  gtRegister(_spark.sqlContext)

  /** This is here so we can test writing UDF generated/modified GeoTrellis types to ensure they are Parquet compliant. */
  def write(df: Dataset[_]): Unit = {
    val sanitized = df.select(df.columns.map(c ⇒ col(c).as(c.replaceAll("[ ,;{}()\n\t=]", "_"))): _*)
    val dest = Files.createTempFile("GTSQL", ".parquet")
    print(s"Writing '${sanitized.columns.mkString(", ")}' to '$dest'...")
    sanitized.write.mode(SaveMode.Overwrite).parquet(dest.toString)
    val rows = df.sparkSession.read.parquet(dest.toString).count()
    println(s" it has $rows row(s)")
  }

  implicit class DFExtras(df: DataFrame) {
    def firstTile: Tile = df.collect().head.getAs[Tile](0)
  }

  import _spark.implicits._

  describe("GeoTrellis UDTs") {
    it("should create constant tiles") {
      val query = sql("select st_makeConstantTile(1, 10, 10, 'int8raw')")
      write(query)
      val tile = query.firstTile
      assert((tile.cellType === ByteCellType) (org.scalactic.Equality.default))
    }

    it("should evaluate UDF on tile") {
      val query = sql("select st_focalSum(st_makeConstantTile(1, 10, 10, 'int8raw'), 4)")
      write(query)
      val tile = query.firstTile

      assert(tile.cellType === ByteCellType)
    }

    it("should report dimensions") {
      val query = sql(
        """|select st_gridRows(tiles) as rows, st_gridCols(tiles) as cols from (
           |select st_makeConstantTile(1, 10, 10, 'int8raw') as tiles)
           |""".stripMargin)
      write(query)
      assert(query.as[(Int, Int)].collect().head === ((10, 10)))
    }

    it("should generate multiple rows") {
      val query = sql("select explode(st_makeTiles(3))")
      write(query)
      assert(query.count === 3)
    }

    it("should explode rows") {
      val query = sql(
        """select st_explodeTile(
          |  st_makeConstantTile(1, 10, 10, 'int8raw'),
          |  st_makeConstantTile(2, 10, 10, 'int8raw')
          |)
          |""".stripMargin)
      write(query)
      assert(query.select("cell_0", "cell_1").as[(Double, Double)].collect().forall(_ == ((1.0, 2.0))))
      val query2 = sql(
        """|select st_gridRows(tiles) as rows, st_gridCols(tiles) as cols, st_explodeTile(tiles)  from (
           |select st_makeConstantTile(1, 10, 10, 'int8raw') as tiles)
           |""".stripMargin)
      write(query2)
      assert(query2.columns.size === 5)

      val df = Seq[(Tile, Tile)]((byteArrayTile, byteArrayTile)).toDF("tile1", "tile2")
      val exploded = df.select(explodeTile($"tile1", $"tile2"))
      //exploded.printSchema()
      assert(exploded.columns.size === 4)
      assert(exploded.count() === 9)
      write(exploded)
    }

    it("should code RDD[(Int, Tile)]") {
      val ds = Seq((1, byteArrayTile: Tile)).toDS
      write(ds)
      assert(ds.toDF.as[(Int, Tile)].collect().head === ((1, byteArrayTile)))
    }

    it("should code RDD[Tile]") {
      val rdd = sc.makeRDD(Seq(byteArrayTile: Tile))
      val ds = rdd.toDF("tile")
      write(ds)
      assert(ds.toDF.as[Tile].collect().head === byteArrayTile)
    }

    it("should code RDD[MultibandTile]") {
      val rdd = sc.makeRDD(Seq(multibandTile))
      val ds = rdd.toDS()
      write(ds)
      assert(ds.toDF.as[MultibandTile].collect().head === multibandTile)
    }

    it("should code RDD[TileFeature]") {
      val thing = TileFeature(byteArrayTile: Tile, "meta")
      val ds = Seq(thing).toDS()
      write(ds)
      assert(ds.toDF.as[TileFeature[Tile, String]].collect().head === thing)
    }

    it("should code RDD[Extent]") {
      val ds = Seq(extent).toDS()
      write(ds)
      assert(ds.toDF.as[Extent].collect().head === extent)
    }

    it("should code RDD[ProjectedExtent]") {
      val ds = Seq(pe).toDS()
      write(ds)
      assert(ds.toDF.as[ProjectedExtent].collect().head === pe)
    }

    it("should code RDD[TemporalProjectedExtent]") {
      val ds = Seq(tpe).toDS()
      write(ds)
      assert(ds.toDF.as[TemporalProjectedExtent].collect().head === tpe)
    }

    it("should flatten stuff") {
      withClue("Extent") {
        val ds = Seq(extent).toDS()
        assert(ds.select(flatten(asStruct($"value".as[Extent]))).select("xmax").as[Double].collect().head === extent.xmax)
        assert(ds.select(flatten($"value".as[Extent])).select("xmax").as[Double].collect().head === extent.xmax)
        assert(ds.select(expr("st_flattenExtent(value)")).select("xmax").as[Double].collect().head === extent.xmax)
      }
      withClue("ProjectedExtent") {
        val ds = Seq(pe).toDS()
        val flattened = ds
          .select(flatten($"value".as[ProjectedExtent]))
          .select(flatten($"extent".as[Extent]), $"crs")
        assert(flattened.select("xmax").as[Double].collect().head === pe.extent.xmax)
      }
      withClue("ProjectedExtent") {
        val ds = Seq(tpe).toDS()
        val flattened = ds
          .select(flatten($"value".as[TemporalProjectedExtent]))
          .select(flatten($"extent".as[Extent]), $"time", $"crs")
        implicit val enc = Encoders.TIMESTAMP

        assert(flattened.select("time").as[Timestamp].collect().head === new Timestamp(tpe.instant))
      }
    }

    it("should support local min/max") {
      val ds = Seq[Tile](byteArrayTile, byteConstantTile).toDF("tiles")
      ds.createOrReplaceTempView("tmp")

      withClue("max") {
        val max = ds.agg(localMax($"tiles"))
        val expected = Max(byteArrayTile, byteConstantTile)
        write(max)
        assert(max.as[Tile].first() === expected)

        val sqlMax = sql("select st_localMax(tiles) from tmp")
        assert(sqlMax.as[Tile].first() === expected)

      }

      withClue("min") {
        val min = ds.agg(localMin($"tiles"))
        val expected = Min(byteArrayTile, byteConstantTile)
        write(min)
        assert(min.as[Tile].first() === Min(byteArrayTile, byteConstantTile))

        val sqlMin = sql("select st_localMin(tiles) from tmp")
        assert(sqlMin.as[Tile].first() === expected)
      }
    }

    it("should compute tile statistics") {
      val ds = Seq.fill[Tile](3)(UDFs.randomTile(5, 5, "float32")).toDS()
      val means1 = ds.select(statistics($"value")).map(_.mean).collect
      val means2 = ds.select(tileMean($"value")).collect
      assert(means1 === means2)


      ds.toDF.mapPartitions()
    }

    it("should list supported cell types") {
      val ct = sql("select explode(st_cellTypes())").as[String].collect
      forEvery(UDFs.cellTypes()) { c ⇒
        assert(ct.contains(c))
      }
    }

    it("should generate a histogram") {
      val ds = Seq[Tile](UDFs.randomTile(5, 5, "float32")).toDF("tiles")
      ds.createOrReplaceTempView("tmp")

      val r1 = ds.select(histogram($"tiles").as[Histogram[Double]])
      write(r1)


      val r2 = sql("select st_histogram(tiles) from tmp")//.as[Histogram[Double]]
      write(r2)

    }
  }
}
