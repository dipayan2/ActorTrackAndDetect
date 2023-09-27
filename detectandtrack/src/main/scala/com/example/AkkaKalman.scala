//#full-example
//#currently using example from https://commons.apache.org/proper/commons-math/userguide/filter.html
package com.example


import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import org.apache.commons.math3.filter._
import org.apache.commons.math3.linear._
import org.apache.commons.math3.random.{JDKRandomGenerator, RandomGenerator}

final case class Measurement(realVec: ArrayRealVector)
final case class StartGenerate()

object Estimator {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1.0
  val processNoise = 1e-5
  
  // A = [ 1 ]
  val A = new Array2DRowRealMatrix(Array(1.0))

  // B = null
  val B: RealMatrix = null

  // H = [ 1 ]
  val H = new Array2DRowRealMatrix(Array(1.0))

  // x = [ 10 ]
  var x = new ArrayRealVector(Array(constantVoltage))

  // Q = [ 1e-5 ]
  val Q = new Array2DRowRealMatrix(Array(processNoise))

  // P = [ 1 ]
  val P0 = new Array2DRowRealMatrix(Array(1.0))

  // R = [ 0.1 ]
  val R = new Array2DRowRealMatrix(Array(measurementNoise))

  val pm = new DefaultProcessModel(A, B, Q, x, P0)
  val mm = new DefaultMeasurementModel(H, R)
  val filter = new KalmanFilter(pm, mm)

  def apply(): Behavior[Measurement] =
  Behaviors.setup { context =>
    Behaviors.receiveMessage { message =>
      filter.predict();

      filter.correct(message.realVec);

      val voltage = filter.getStateEstimation()(0)
      context.log.info(s"Estimate: ${voltage}")
      Behaviors.same
    }
  }
}

object Generator {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1
  val processNoise = 1e-5

  // x = [ 10 ]
  var x = new ArrayRealVector(Array(constantVoltage))

  // Process and measurement noise vectors
  val pNoise = new ArrayRealVector(Array(0.0))
  val mNoise = new ArrayRealVector(Array(0.0))

  def apply(estimator: ActorRef[Measurement]): Behavior[Measurement] =
  Behaviors.setup { context =>
    for (i <- 1 to 15) {
      // simulate the process
      pNoise.setEntry(0, processNoise * Random.nextGaussian());

      // x = A * x + p_noise
      x.add(pNoise)

      // simulate the measurement
      mNoise.setEntry(0, measurementNoise * Random.nextGaussian());

      // z = H * x + m_noise
      val z = x.add(mNoise)

      context.log.info(s"Sending number: ${z.getEntry(0)}")
      estimator ! Measurement(z)
    }
    Behaviors.stopped
  }
}

object KalmanMain {
  def apply(): Behavior[StartGenerate] =
    Behaviors.setup { context =>
      //#create-actors
      val estimator = context.spawn(Estimator(), "estimator")
      val generator = context.spawn(Generator(estimator), "generator")
      //#create-actors
      Behaviors.same
    }
}

//#main-class
object AkkaQuickstart extends App {
  val kalmanMain = ActorSystem[StartGenerate](KalmanMain(), "generator-estimator")
  kalmanMain ! StartGenerate()
}
//#main-class
//#full-example
