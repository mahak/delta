/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.commands.convert

import java.lang.{Boolean => JBoolean, Double => JDouble, Float => JFloat, Integer => JInt, Long => JLong}
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.{HashMap => JHashMap, List => JList, Map => JMap}

import scala.collection.JavaConverters._

import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.util.JsonUtils
import org.apache.iceberg.{DataFile, FileContent, FileFormat, PartitionData, PartitionSpec, Schema, StructLike}
import org.apache.iceberg.transforms._
import org.apache.iceberg.types.Conversions
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Type.TypeID
import org.apache.iceberg.types.Types._

import org.apache.spark.SparkFunSuite
import org.apache.spark.internal.config.ConfigEntry
import org.apache.spark.sql.test.SharedSparkSession

class IcebergStatsUtilsSuite extends SparkFunSuite with SharedSparkSession {

  private val StatsAllowTypes =
    IcebergStatsUtils.typesAllowStatsConversion(statsDisallowTypes = Set.empty)

  test("stats conversion from basic columns") {
    val icebergSchema = new Schema(10, Seq[NestedField](
      NestedField.required(1, "col_int", IntegerType.get),
      NestedField.required(2, "col_long", LongType.get),
      NestedField.required(3, "col_st", StringType.get),
      NestedField.required(4, "col_boolean", BooleanType.get),
      NestedField.required(5, "col_float", FloatType.get),
      NestedField.required(6, "col_double", DoubleType.get),
      NestedField.required(7, "col_date", DateType.get),
      NestedField.required(8, "col_binary", BinaryType.get),
      NestedField.required(9, "col_strt", StructType.of(
        NestedField.required(10, "sc_int", IntegerType.get),
        NestedField.required(11, "sc_int2", IntegerType.get)
      )),
      NestedField.required(12, "col_array",
        ListType.ofRequired(13, IntegerType.get)),
      NestedField.required(14, "col_map",
        MapType.ofRequired(15, 16, IntegerType.get, StringType.get))).asJava
    )

    val minMap = Map(
      Integer.valueOf(1) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(-5)),
      Integer.valueOf(2) -> Conversions.toByteBuffer(LongType.get, JLong.valueOf(-4)),
      Integer.valueOf(3) -> Conversions.toByteBuffer(StringType.get, "minval"),
      Integer.valueOf(4) -> Conversions.toByteBuffer(BooleanType.get, JBoolean.FALSE),
      Integer.valueOf(5) -> Conversions.toByteBuffer(FloatType.get, JFloat.valueOf("0.001")),
      Integer.valueOf(6) -> Conversions.toByteBuffer(DoubleType.get, JDouble.valueOf("0.0001")),
      Integer.valueOf(7) -> Conversions.toByteBuffer(DateType.get, JInt.valueOf(12800)),
      Integer.valueOf(8) -> Conversions.toByteBuffer(BinaryType.get,
        ByteBuffer.wrap(Array(1, 2, 3, 4))),
      Integer.valueOf(10) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(-1)),
      Integer.valueOf(11) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(-1))
    )
    val maxMap = Map(
      Integer.valueOf(1) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(5)),
      Integer.valueOf(2) -> Conversions.toByteBuffer(LongType.get, JLong.valueOf(4)),
      Integer.valueOf(3) -> Conversions.toByteBuffer(StringType.get, "maxval"),
      Integer.valueOf(4) -> Conversions.toByteBuffer(BooleanType.get, JBoolean.TRUE),
      Integer.valueOf(5) -> Conversions.toByteBuffer(FloatType.get, JFloat.valueOf("10.001")),
      Integer.valueOf(6) -> Conversions.toByteBuffer(DoubleType.get, JDouble.valueOf("10.0001")),
      Integer.valueOf(7) -> Conversions.toByteBuffer(DateType.get, JInt.valueOf(13800)),
      Integer.valueOf(8) -> Conversions.toByteBuffer(BinaryType.get,
        ByteBuffer.wrap(Array(2, 2, 3, 4))),
      Integer.valueOf(10) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(128)),
      Integer.valueOf(11) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(512))
    )
    val nullCountMap = Map(
      Integer.valueOf(1) -> JLong.valueOf(0),
      Integer.valueOf(2) -> JLong.valueOf(1),
      Integer.valueOf(3) -> JLong.valueOf(2),
      Integer.valueOf(5) -> JLong.valueOf(3),
      Integer.valueOf(6) -> JLong.valueOf(4),
      Integer.valueOf(7) -> JLong.valueOf(5),
      Integer.valueOf(8) -> JLong.valueOf(6),
      Integer.valueOf(10) -> JLong.valueOf(7),
      Integer.valueOf(11) -> JLong.valueOf(8),
      Integer.valueOf(12) -> JLong.valueOf(9),
      Integer.valueOf(14) -> JLong.valueOf(10)
    )

    val deltaStats = IcebergStatsUtils.icebergStatsToDelta(
      icebergSchema,
      1251,
      Some(minMap),
      Some(maxMap),
      Some(nullCountMap),
      statsAllowTypes = StatsAllowTypes
    )

    val actualStatsObj = JsonUtils.fromJson[StatsObject](deltaStats)
    val expectedStatsObj = JsonUtils.fromJson[StatsObject](
      """{"numRecords":1251,
        |"maxValues":{"col_date":"2005-01-17","col_int":-5,"col_double":1.0E-4,
        |"col_float":0.001,"col_long":-4,"col_strt":{"sc_int":-1,"sc_int2":-1},
        |"col_boolean":false,"col_st":"minval","col_binary":"AQIDBA=="},
        |"minValues":{"col_date":"2007-10-14","col_int":5,"col_double":10.0001,
        |"col_float":10.001,"col_long":4,"col_strt":{"sc_int":128,"sc_int2":512},
        |"col_boolean":true,"col_st":"maxval","col_binary":"AgIDBA=="},
        |"nullCount":{"col_int":0,"col_double":4,"col_date":5,"col_float":3,"col_long":1,
        |"col_strt":{"sc_int":7,"sc_int2":8},"col_st":2,"col_binary":6,"col_array":9,"col_map":10}}
        |""".stripMargin.replaceAll("\n", ""))
    assertResult(expectedStatsObj)(actualStatsObj)
  }

  test("stats conversion for decimal and timestamp") {
    val icebergSchema = new Schema(10, Seq[NestedField](
      NestedField.required(1, "col_ts", TimestampType.withZone),
      NestedField.required(2, "col_tsnz", TimestampType.withoutZone),
      NestedField.required(3, "col_decimal", DecimalType.of(10, 5))
    ).asJava)
    val deltaStats = IcebergStatsUtils.icebergStatsToDelta(
      icebergSchema,
      1251,
      minMap = Some(Map(
        Integer.valueOf(1) ->
          Conversions.toByteBuffer(TimestampType.withZone, JLong.valueOf(1734391979000000L)),
        Integer.valueOf(2) ->
          Conversions.toByteBuffer(TimestampType.withoutZone, JLong.valueOf(1734391979000000L)),
        Integer.valueOf(3) ->
          Conversions.toByteBuffer(DecimalType.of(10, 5), new BigDecimal("3.44141"))
      )),
      maxMap = Some(Map(
        Integer.valueOf(1) ->
          Conversions.toByteBuffer(TimestampType.withZone, JLong.valueOf(1734394979000000L)),
        Integer.valueOf(2) ->
          Conversions.toByteBuffer(TimestampType.withoutZone, JLong.valueOf(1734394979000000L)),
        Integer.valueOf(3) ->
          Conversions.toByteBuffer(DecimalType.of(10, 5), new BigDecimal("9.99999"))
      )),
      nullCountMap = Some(Map(
        Integer.valueOf(1) -> JLong.valueOf(20),
        Integer.valueOf(2) -> JLong.valueOf(10),
        Integer.valueOf(3) -> JLong.valueOf(31)
      )),
      statsAllowTypes = StatsAllowTypes
    )
    assertResult(
      JsonUtils.fromJson[StatsObject](
        """{"numRecords":1251,
          |"maxValues":{
          | "col_ts":"2024-12-17T00:22:59+00:00",
          | "col_tsnz":"2024-12-17T00:22:59",
          | "col_decimal":9.99999
          | },
          |"minValues":{
          | "col_ts":"2024-12-16T23:32:59+00:00",
          | "col_tsnz":"2024-12-16T23:32:59",
          | "col_decimal":3.44141
          | },
          |"nullCount":{"col_ts":20,"col_tsnz":10,"col_decimal":31}}""".stripMargin))(
      JsonUtils.fromJson[StatsObject](deltaStats))
  }

  test("stats conversion when value is missing or is null") {
    val icebergSchema = new Schema(10, Seq[NestedField](
      NestedField.required(1, "col_int", IntegerType.get),
      NestedField.required(2, "col_long", LongType.get),
      NestedField.required(3, "col_st", StringType.get)
    ).asJava)
    val deltaStats = IcebergStatsUtils.icebergStatsToDelta(
      icebergSchema,
      1251,
      minMap = Some(Map(
        Integer.valueOf(1) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(-5)),
        Integer.valueOf(2) -> Conversions.toByteBuffer(LongType.get, null),
        Integer.valueOf(3) -> null
      )),
      maxMap = Some(Map(
        Integer.valueOf(1) -> Conversions.toByteBuffer(IntegerType.get, JInt.valueOf(5)),
        // stats for value 2 is missing
        Integer.valueOf(3) -> Conversions.toByteBuffer(StringType.get, "maxval"),
        Integer.valueOf(5) -> Conversions.toByteBuffer(StringType.get, "maxval")
      )),
      nullCountMap = Some(Map(
        Integer.valueOf(1) -> JLong.valueOf(0),
        Integer.valueOf(2) -> null,
        Integer.valueOf(3) -> JLong.valueOf(2),
        Integer.valueOf(5) -> JLong.valueOf(3)
      )),
      statsAllowTypes = StatsAllowTypes
    )
    assertResult(
      JsonUtils.fromJson[StatsObject](
        """{"numRecords":1251,
          |"maxValues":{"col_int":5,"col_st":"maxval"},
          |"minValues":{"col_int":-5},
          |"nullCount":{"col_int":0,"col_st":2}}
          |""".stripMargin))(
      JsonUtils.fromJson[StatsObject](deltaStats))
  }

  private def testStatsConversion(
      expectedStatsJson: String, dataFile: DataFile, icebergSchema: Schema): Unit = {
    val expectedStats = JsonUtils.fromJson[StatsObject](expectedStatsJson)
    val actualStats =
      IcebergStatsUtils.icebergStatsToDelta(
          icebergSchema, dataFile, StatsAllowTypes, shouldSkipForFile = _ => false
        )
        .map(JsonUtils.fromJson[StatsObject](_))
        .get
    assertResult(expectedStats)(actualStats)
  }

  test("stats conversion while DataFile misses the stats fields") {
    val icebergSchema = new Schema(10, Seq[NestedField](
      NestedField.required(1, "col_int", IntegerType.get),
      NestedField.required(2, "col_long", LongType.get),
      NestedField.required(3, "col_st", StringType.get)
    ).asJava)
    val expectedStatsJson =
      """{"numRecords":0,"maxValues":{"col_int":100992003},
        |"minValues":{"col_int":100992003},"nullCount":{"col_int":2}}"""
        .stripMargin
    testStatsConversion(expectedStatsJson, DummyDataFile(), icebergSchema)

    val expectedStatsWithoutUpperBound =
      """{"numRecords":0,"minValues":{"col_int":100992003},
        |"nullCount":{"col_int":2}}"""
        .stripMargin
    testStatsConversion(
      expectedStatsWithoutUpperBound, DummyDataFile(upperBounds = null), icebergSchema
    )
    testStatsConversion(
      expectedStatsWithoutUpperBound,
      DummyDataFile(upperBounds = new JHashMap[Integer, ByteBuffer]()),
      icebergSchema
    )

    val expectedStatsWithoutLowerBound =
      """{"numRecords":0,"maxValues":{"col_int":100992003},
        |"nullCount":{"col_int":2}}"""
        .stripMargin
    testStatsConversion(
      expectedStatsWithoutLowerBound, DummyDataFile(lowerBounds = null), icebergSchema
    )
    testStatsConversion(
      expectedStatsWithoutLowerBound,
      DummyDataFile(lowerBounds = new JHashMap[Integer, ByteBuffer]()),
      icebergSchema
    )

    val expectedStatsWithoutNullCounts =
      """{"numRecords":0,"maxValues":{"col_int":100992003},
        |"minValues":{"col_int":100992003}}"""
        .stripMargin
    testStatsConversion(
      expectedStatsWithoutNullCounts, DummyDataFile(nullValueCounts = null), icebergSchema
    )
    testStatsConversion(
      expectedStatsWithoutNullCounts,
      DummyDataFile(nullValueCounts = new JHashMap[Integer, JLong]()),
      icebergSchema
    )
  }
}

private case class StatsObject(
    numRecords: Long,
    maxValues: Map[String, Any],
    minValues: Map[String, Any],
    nullCount: Map[String, Long])

private case class DummyDataFile(
    upperBounds: JMap[JInt, ByteBuffer] =
    Map(JInt.valueOf(1) -> ByteBuffer.wrap(Array(3, 4, 5, 6))).asJava,
    lowerBounds: JMap[JInt, ByteBuffer] =
    Map(JInt.valueOf(1) -> ByteBuffer.wrap(Array(3, 4, 5, 6))).asJava,
    nullValueCounts: JMap[JInt, JLong] =
    Map(JInt.valueOf(1) -> JLong.valueOf(2)).asJava) extends DataFile {
  override def pos: JLong = 0L
  override def specId: Int = 0
  override def path: String = "dummy"
  override def recordCount: Long = 0
  override def fileSizeInBytes: Long = 0
  override def content: FileContent = FileContent.DATA
  override def format: FileFormat = FileFormat.PARQUET
  override def partition: StructLike = null
  override def columnSizes: JMap[JInt, JLong] = null
  override def valueCounts: JMap[JInt, JLong] = null
  override def nanValueCounts: JMap[JInt, JLong] = null
  override def keyMetadata: ByteBuffer = null
  override def splitOffsets: JList[JLong] = null
  override def copy: DataFile = this.copy
  override def copyWithoutStats: DataFile = this.copy
}
