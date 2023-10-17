//#full-example
//#currently using example from https://commons.apache.org/proper/commons-math/userguide/filter.html
package com.example


import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._
import scala.util.Random
import org.apache.commons.math3.filter._
import org.apache.commons.math3.linear._
import org.apache.commons.math3.random.{JDKRandomGenerator, RandomGenerator}
import akka.actor.Actor

sealed trait EstimatorReceivable
final case class MyAddr(addr: ActorRef[EstimatorReceivable]) extends EstimatorReceivable
final case class Measurement(realVec: ArrayRealVector, replyTo: ActorRef[Estimate]) extends EstimatorReceivable
final case object Timeout extends EstimatorReceivable
final case class Estimate(estimate: Double)
final case class StartGenerate()

object Estimator {
  /*
    Behaviour value. fixed value associated with each Estimator
  */
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


  var FellowActor: Set[ActorRef[EstimatorReceivable]] = Set() // Created a set of actor to add for collecting and sending information 

  private def addToSet(ref: ActorRef[EstimatorReceivable]): Unit ={
    FellowActor = FellowActor+ref
  }

  def apply(): Behavior[EstimatorReceivable] = idle()

  def estimating(realVec: ArrayRealVector, replyTo: ActorRef[Estimate]): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
    filter.predict();

    filter.correct(realVec);

    val voltage = filter.getStateEstimation()(0)
    replyTo ! Estimate(voltage)
    Behaviors.same
  }

  def addingNewFellow(addr: ActorRef[EstimatorReceivable]): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
    addToSet(addr)
    context.log.info("Adding a new fellow to my list")
    Behaviors.same

  }

  def idle(): Behavior[EstimatorReceivable] = Behaviors.withTimers { timer =>
    timer.startSingleTimer(Timeout, 5.second)
    Behaviors.receive { (context, message) =>
      message match {
        case Measurement(realVec, replyTo) =>
          estimating(realVec, replyTo)
        
        case MyAddr(addr) =>
          addingNewFellow(addr)
                 
        case Timeout =>
          context.log.info("Timed out...")
          Behaviors.stopped
      }
    }
  }
}

object Generator {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1
  val processNoise = 1e-5
  val numMeasurements = 10

  // x = [ 10 ]
  var x = new ArrayRealVector(Array(constantVoltage))

  // Process and measurement noise vectors
  val pNoise = new ArrayRealVector(Array(0.0))
  val mNoise = new ArrayRealVector(Array(0.0))

  def apply(estimator: ActorRef[Measurement]): Behavior[Estimate] = generating(estimator, 0)

  private def generating(estimator: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] =
  Behaviors.setup { context =>
    // Randomly crash generator to test timeout
    if (Random.nextInt() % 3 == 0 || messageCounter == numMeasurements) {
      Behaviors.stopped
    } else {
      // simulate the process
      pNoise.setEntry(0, processNoise * Random.nextGaussian());

      // x = A * x + p_noise
      x.add(pNoise)

      // simulate the measurement
      mNoise.setEntry(0, measurementNoise * Random.nextGaussian());

      // z = H * x + m_noise
      val z = x.add(mNoise)

      context.log.info(s"Sending measurement: ${z.getEntry(0)}")
      estimator ! Measurement(z, context.self)
      idle(estimator, messageCounter + 1)
    }
  }

  private def idle(estimator: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] = Behaviors.receive { (context, message) =>
    context.log.info(s"Received estimate: ${message.estimate}")
    generating(estimator, messageCounter)
  }
}
/*
Generating the actor system 
*/
object KalmanMain {
  def apply(): Behavior[StartGenerate] =
    Behaviors.setup { context =>
      //#create-actors
      val estimator1_1 = context.spawn(Estimator(), "estimator")
      val estimator1_2 = context.spawn(Estimator(), "estimator1")
      estimator1_1 ! MyAddr(estimator1_2)
      estimator1_2 ! MyAddr(estimator1_1)
      val generator = context.spawn(Generator(estimator1_1), "generator")
      val generator1 = context.spawn(Generator(estimator1_2), "generator1")
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
