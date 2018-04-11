package clustering4ever.stats

import _root_.scala.math.{sqrt, pow}
import _root_.clustering4ever.util.SumArrays
import _root_.scala.collection.mutable
import _root_.clustering4ever.scala.kernels.Kernels
import _root_.clustering4ever.math.distances.ContinuousDistances
import _root_.clustering4ever.math.distances.scalar.Euclidean

object Stats
{

	def reduceColumns(vectors: Array[Array[Double]]) =
	{
		vectors.reduce( (a, b) => for( i <- a.indices.toArray ) yield (a(i) + b(i)) )
	}

	def mean(vectors: Array[Array[Double]]) =
	{
		reduceColumns(vectors).map(_ / vectors.size)
	}

	def sd(vectors: Array[Array[Double]], mean: Array[Double]) =
	{
		sqrt(
			vectors.map( v =>
			{
				var sum = 0D
				for( i <- v.indices ) sum += pow(v(i) - mean(i), 2)
				sum
			}).sum / (vectors.size - 1)
		)
	}

	def reduceMatriceColumns(a: Array[Double], b: Array[Double]) =
	{
		for( i <- a.indices.toArray ) yield (a(i) + b(i))
	}

	def reduceMultipleMatriceColumns(a: Array[Array[Double]], b: Array[Array[Double]]) =
	{
		for( i <- a.indices.toArray ) yield SumArrays.sumArraysNumerics(a(i), b(i))
	}

	def diffDotProduct(v1: Array[Double], v2: Array[Double]) =
	{
		( for( i <- v1.indices.toArray ) yield (v1(i) - v2(i)) ).map(pow(_, 2)).sum			
	}

	type Mean = Array[Double]
	type SD = Double
	type ClusterID = Int
	def obtainGammaByCluster(v: Array[Double], gaussianLawFeaturesSortedByClusterID: Array[(ClusterID, (Mean, SD))], πksortedByClusterID: Array[Double], metric: ContinuousDistances = new Euclidean(true)) =
	{
		val genProb = gaussianLawFeaturesSortedByClusterID.map{ case (clusterID, (meanC, sdC)) => (clusterID, Kernels.gaussianKernel(v, meanC, 1D / (2 * pow(sdC, 2)), metric)) }

		val averaging = genProb.map{ case (clusterID, prob) => prob * πksortedByClusterID(clusterID) }.sum 

		val gammaByCluster = genProb.map{ case (clusterID, prob) => (clusterID, (πksortedByClusterID(clusterID) * prob) / averaging) }

		gammaByCluster
	}

}