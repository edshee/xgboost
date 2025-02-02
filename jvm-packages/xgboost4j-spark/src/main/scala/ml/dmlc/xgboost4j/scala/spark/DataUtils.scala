/*
 Copyright (c) 2014,2021 by Contributors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package ml.dmlc.xgboost4j.scala.spark

import scala.collection.mutable

import ml.dmlc.xgboost4j.{LabeledPoint => XGBLabeledPoint}

import org.apache.spark.HashPartitioner
import org.apache.spark.ml.feature.{LabeledPoint => MLLabeledPoint}
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.types.{FloatType, IntegerType}

object DataUtils extends Serializable {
  private[spark] implicit class XGBLabeledPointFeatures(
      val labeledPoint: XGBLabeledPoint
  ) extends AnyVal {
    /** Converts the point to [[MLLabeledPoint]]. */
    private[spark] def asML: MLLabeledPoint = {
      MLLabeledPoint(labeledPoint.label, labeledPoint.features)
    }

    /**
     * Returns feature of the point as [[org.apache.spark.ml.linalg.Vector]].
     */
    def features: Vector = if (labeledPoint.indices == null) {
      Vectors.dense(labeledPoint.values.map(_.toDouble))
    } else {
      Vectors.sparse(labeledPoint.size, labeledPoint.indices, labeledPoint.values.map(_.toDouble))
    }
  }

  private[spark] implicit class MLLabeledPointToXGBLabeledPoint(
      val labeledPoint: MLLabeledPoint
  ) extends AnyVal {
    /** Converts an [[MLLabeledPoint]] to an [[XGBLabeledPoint]]. */
    def asXGB: XGBLabeledPoint = {
      labeledPoint.features.asXGB.copy(label = labeledPoint.label.toFloat)
    }
  }

  private[spark] implicit class MLVectorToXGBLabeledPoint(val v: Vector) extends AnyVal {
    /**
     * Converts a [[Vector]] to a data point with a dummy label.
     *
     * This is needed for constructing a [[ml.dmlc.xgboost4j.scala.DMatrix]]
     * for prediction.
     */
    def asXGB: XGBLabeledPoint = v match {
      case v: DenseVector =>
        XGBLabeledPoint(0.0f, v.size, null, v.values.map(_.toFloat))
      case v: SparseVector =>
        XGBLabeledPoint(0.0f, v.size, v.indices, v.values.map(_.toFloat))
    }
  }

  private def attachPartitionKey(
      row: Row,
      deterministicPartition: Boolean,
      numWorkers: Int,
      xgbLp: XGBLabeledPoint): (Int, XGBLabeledPoint) = {
    if (deterministicPartition) {
      (math.abs(row.hashCode() % numWorkers), xgbLp)
    } else {
      (1, xgbLp)
    }
  }

  private def repartitionRDDs(
      deterministicPartition: Boolean,
      numWorkers: Int,
      arrayOfRDDs: Array[RDD[(Int, XGBLabeledPoint)]]): Array[RDD[XGBLabeledPoint]] = {
    if (deterministicPartition) {
      arrayOfRDDs.map {rdd => rdd.partitionBy(new HashPartitioner(numWorkers))}.map {
        rdd => rdd.map(_._2)
      }
    } else {
      arrayOfRDDs.map(rdd => {
        if (rdd.getNumPartitions != numWorkers) {
          rdd.map(_._2).repartition(numWorkers)
        } else {
          rdd.map(_._2)
        }
      })
    }
  }

  /** Packed parameters used by [[convertDataFrameToXGBLabeledPointRDDs]] */
  private[spark] case class PackedParams(labelCol: Column,
    featuresCol: Column,
    weight: Column,
    baseMargin: Column,
    group: Option[Column],
    numWorkers: Int,
    deterministicPartition: Boolean)

  /**
   * convertDataFrameToXGBLabeledPointRDDs converts DataFrames to an array of RDD[XGBLabeledPoint]
   *
   * First, it serves converting each instance of input into XGBLabeledPoint
   * Second, it repartition the RDD to the number workers.
   *
   */
  private[spark] def convertDataFrameToXGBLabeledPointRDDs(
    packedParams: PackedParams,
    dataFrames: DataFrame*): Array[RDD[XGBLabeledPoint]] = {

    packedParams match {
      case j @ PackedParams(labelCol, featuresCol, weight, baseMargin, group, numWorkers,
      deterministicPartition) =>
        val selectedColumns = group.map(groupCol => Seq(labelCol.cast(FloatType),
          featuresCol,
          weight.cast(FloatType),
          groupCol.cast(IntegerType),
          baseMargin.cast(FloatType))).getOrElse(Seq(labelCol.cast(FloatType),
          featuresCol,
          weight.cast(FloatType),
          baseMargin.cast(FloatType)))
        val arrayOfRDDs = dataFrames.toArray.map {
          df => df.select(selectedColumns: _*).rdd.map {
            case row @ Row(label: Float, features: Vector, weight: Float, group: Int,
            baseMargin: Float) =>
              val (size, indices, values) = features match {
                case v: SparseVector => (v.size, v.indices, v.values.map(_.toFloat))
                case v: DenseVector => (v.size, null, v.values.map(_.toFloat))
              }
              val xgbLp = XGBLabeledPoint(label, size, indices, values, weight, group, baseMargin)
              attachPartitionKey(row, deterministicPartition, numWorkers, xgbLp)
            case row @ Row(label: Float, features: Vector, weight: Float, baseMargin: Float) =>
              val (size, indices, values) = features match {
                case v: SparseVector => (v.size, v.indices, v.values.map(_.toFloat))
                case v: DenseVector => (v.size, null, v.values.map(_.toFloat))
              }
              val xgbLp = XGBLabeledPoint(label, size, indices, values, weight,
                baseMargin = baseMargin)
              attachPartitionKey(row, deterministicPartition, numWorkers, xgbLp)
          }
        }
        repartitionRDDs(deterministicPartition, numWorkers, arrayOfRDDs)

      case _ => throw new IllegalArgumentException("Wrong PackedParams") // never reach here
    }

  }

  private[spark] def processMissingValues(
      xgbLabelPoints: Iterator[XGBLabeledPoint],
      missing: Float,
      allowNonZeroMissing: Boolean): Iterator[XGBLabeledPoint] = {
    if (!missing.isNaN) {
      removeMissingValues(verifyMissingSetting(xgbLabelPoints, missing, allowNonZeroMissing),
        missing, (v: Float) => v != missing)
    } else {
      removeMissingValues(verifyMissingSetting(xgbLabelPoints, missing, allowNonZeroMissing),
        missing, (v: Float) => !v.isNaN)
    }
  }

  private[spark] def processMissingValuesWithGroup(
      xgbLabelPointGroups: Iterator[Array[XGBLabeledPoint]],
      missing: Float,
      allowNonZeroMissing: Boolean): Iterator[Array[XGBLabeledPoint]] = {
    if (!missing.isNaN) {
      xgbLabelPointGroups.map {
        labeledPoints => processMissingValues(
          labeledPoints.iterator,
          missing,
          allowNonZeroMissing
        ).toArray
      }
    } else {
      xgbLabelPointGroups
    }
  }

  private def removeMissingValues(
    xgbLabelPoints: Iterator[XGBLabeledPoint],
    missing: Float,
    keepCondition: Float => Boolean): Iterator[XGBLabeledPoint] = {
    xgbLabelPoints.map { labeledPoint =>
      val indicesBuilder = new mutable.ArrayBuilder.ofInt()
      val valuesBuilder = new mutable.ArrayBuilder.ofFloat()
      for ((value, i) <- labeledPoint.values.zipWithIndex if keepCondition(value)) {
        indicesBuilder += (if (labeledPoint.indices == null) i else labeledPoint.indices(i))
        valuesBuilder += value
      }
      labeledPoint.copy(indices = indicesBuilder.result(), values = valuesBuilder.result())
    }
  }

  private def verifyMissingSetting(
    xgbLabelPoints: Iterator[XGBLabeledPoint],
    missing: Float,
    allowNonZeroMissing: Boolean): Iterator[XGBLabeledPoint] = {
    if (missing != 0.0f && !allowNonZeroMissing) {
      xgbLabelPoints.map(labeledPoint => {
        if (labeledPoint.indices != null) {
          throw new RuntimeException(s"you can only specify missing value as 0.0 (the currently" +
            s" set value $missing) when you have SparseVector or Empty vector as your feature" +
            s" format. If you didn't use Spark's VectorAssembler class to build your feature " +
            s"vector but instead did so in a way that preserves zeros in your feature vector " +
            s"you can avoid this check by using the 'allow_non_zero_for_missing parameter'" +
            s" (only use if you know what you are doing)")
        }
        labeledPoint
      })
    } else {
      xgbLabelPoints
    }
  }


}
