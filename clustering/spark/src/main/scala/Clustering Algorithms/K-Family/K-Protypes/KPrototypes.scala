package clustering4ever.spark.clustering.kprototypes

import scala.collection.{immutable, mutable}
import scala.util.Random
import scala.annotation.meta.param
import scala.reflect.ClassTag
import scala.math.{min, max}
import org.apache.spark.{SparkContext, HashPartitioner}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import clustering4ever.clustering.ClusteringAlgorithms
import clustering4ever.util.SumArrays
import clustering4ever.clustering.DataSetsTypes
import clustering4ever.math.distances.mixt.HammingAndEuclidean
import clustering4ever.math.distances.MixtDistance
import clustering4ever.scala.measurableclass.BinaryScalarVector
import clustering4ever.stats.Stats
import clustering4ever.scala.clusterizables.MixtClusterizable

/**
 * @author Beck Gaël
 * The famous K-Means using a user-defined dissmilarity measure.
 * @param data : an Array with and ID and the vector
 * @param k : number of clusters
 * @param epsilon : minimal threshold under which we consider a centroid has converged
 * @param iterMax : maximal number of iteration
 * @param metric : a defined dissimilarity measure, it can be custom by overriding MixtDistance distance function
 **/
class KPrototypes[ID: Numeric, Obj, Vb <: Seq[Int], Vs <: Seq[Double], V <: BinaryScalarVector[Vb, Vs] : ClassTag](
	@transient val sc: SparkContext,
	dataIn: RDD[MixtClusterizable[ID, Obj, Vb, Vs, V]],
	var k: Int,
	var epsilon: Double,
	var maxIter: Int,
	var metric: MixtDistance[Vb, Vs, V],
	var persistanceLVL: StorageLevel = StorageLevel.MEMORY_ONLY
) extends ClusteringAlgorithms[Long]
{
	private[this] val data = dataIn.map(_.vector).persist(persistanceLVL)

	private[this] def obtainNearestModID(v: V, centers: mutable.HashMap[Int, V]): Int = centers.minBy{ case(clusterID, mod) => metric.d(mod, v) }._1

	def run(): KPrototypesModel[Vb, Vs, V] =
	{
		val dimScalar = data.first.scalar.size
		val dimBinary = data.first.binary.size
		
		def initializationCenters() =
		{
			val vectorRange = (0 until dimScalar).toBuffer
			val kRange = (0 until k)
			val binaryModes = kRange.map( clusterID => (clusterID, Seq.fill(dimBinary)(Random.nextInt(2)).asInstanceOf[Vb]) )

			val (minv, maxv) = data.map( v =>
			{
				val vector = v.scalar.toBuffer
				(vector, vector)
			}).reduce( (minMaxa, minMaxb) => vectorRange.map( i => Stats.obtainIthMinMax(i, minMaxa, minMaxb) ).unzip )

			val ranges = minv.zip(maxv).map{ case (min, max) => (max - min, min) }.toSeq
			val scalarCentroids = kRange.map( clusterID => (clusterID, ranges.map{ case (range, min) => Random.nextDouble * range + min }.asInstanceOf[Vs]) )

			mutable.HashMap(binaryModes.zip(scalarCentroids).map{ case ((clusterID, binaryVector), (_, scalarVector)) => (clusterID, (new BinaryScalarVector[Vb, Vs](binaryVector, scalarVector)).asInstanceOf[V]) }:_*)
		}
		
		val centers = initializationCenters()
		val centersUpdated = centers.clone
		val clustersCardinality = centers.map{ case (clusterID, _) => (clusterID, 0L) }
		var cpt = 0
		var allModHaveConverged = false
		while( cpt < maxIter && ! allModHaveConverged )
		{
			if( metric.isInstanceOf[HammingAndEuclidean[Vb, Vs, V]] )
			{
				val info = data.map( v => (obtainNearestModID(v, centers), (1L, v)) ).reduceByKey{ case ((sum1, v1), (sum2, v2)) =>
				{
					(
						sum1 + sum2,
						{
							val binaryVector = SumArrays.sumArraysNumericsGen[Int, Vb](v1.binary, v2.binary)
							val scalarVector = SumArrays.sumArraysNumericsGen[Double, Vs](v1.scalar, v2.scalar)
							(new BinaryScalarVector[Vb, Vs](binaryVector, scalarVector)).asInstanceOf[V]
						}
					)
				}}.map{ case (clusterID, (cardinality, preMean)) =>
				{
					(
						clusterID,
						{
							// Majority Vote for Hamming Distance
							val binaryVector = preMean.binary.map( v => if( v * 2 > cardinality ) 1 else 0 ).asInstanceOf[Vb]
							// Mean for Euclidean Distance
							val scalarVector = preMean.scalar.map(_ / cardinality).asInstanceOf[Vs]
							(new BinaryScalarVector[Vb, Vs](binaryVector, scalarVector)).asInstanceOf[V]
						},
						cardinality
					)

				}}.collect

				info.foreach{ case (clusterID, mean, cardinality) =>
				{
					centersUpdated(clusterID) = mean
					clustersCardinality(clusterID) = cardinality
				}}

				allModHaveConverged = centersUpdated.forall{ case (clusterID, uptMod) => metric.d(centers(clusterID), uptMod) <= epsilon }
				
				centersUpdated.foreach{ case (clusterID, mod) => centers(clusterID) = mod }	
			}
			else println("Results will have no sense or cost O(n²) for the moment with another distance than Euclidean, but we're working on it")

			cpt += 1
		}
		new KPrototypesModel[Vb, Vs, V](centers, metric)
	}
}


object KPrototypes extends DataSetsTypes[Long]
{
	def run[ID: Numeric, Obj, Vb <: Seq[Int], Vs <: Seq[Double], V <: BinaryScalarVector[Vb, Vs] : ClassTag](@(transient @param) sc: SparkContext, data: RDD[MixtClusterizable[ID, Obj, Vb, Vs, V]], k: Int, epsilon: Double, maxIter: Int, metric: MixtDistance[Vb, Vs, V], persistanceLVL: StorageLevel = StorageLevel.MEMORY_ONLY): KPrototypesModel[Vb, Vs, V] =
	{
		val kPrototypes = new KPrototypes(sc, data, k, epsilon, maxIter, metric, persistanceLVL)
		val kPrototypesModel = kPrototypes.run()
		kPrototypesModel
	}
}