package com.example

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer
import org.apache.commons.math3.linear.MatrixUtils

final case class Estimate(estimate: Double)
sealed trait EstimatorReceivable
final case object Timeout extends EstimatorReceivable
final case class DroneMeasurement(leader: ActorRef[LocalState], realVec: ArrayRealVector, parentDrone: ActorRef[Estimate]) extends EstimatorReceivable
final case class GlobalState(avgMeasurement: ArrayRealVector, avgCovarianceMatrix: Array2DRowRealMatrix) extends EstimatorReceivable

/**
  * A drone spawns a DroneKalmanEstimator actor to send its sensor measurments to and receives a kalman estimate from
  */
object DroneKalmanEstimator {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1.0
  val processNoise = 1e-5
  
  // A = [ 1 ]
  val A = new Array2DRowRealMatrix(Array(1.0))

  // B = null
  val B = new Array2DRowRealMatrix(Array(0.0))

  // H = [ 1 ]
  val H = new Array2DRowRealMatrix(Array(1.0))

  // x = [ 10 ]
  var xBar = new ArrayRealVector(Array(constantVoltage))

  // Q = [ 1e-5 ]
  val Q = new Array2DRowRealMatrix(Array(processNoise))

  // P = [ 1 ]
  var P = new Array2DRowRealMatrix(Array(1.0))

  // R = [ 0.1 ]
  val R = new Array2DRowRealMatrix(Array(measurementNoise))

  def apply(numNodes: Int): Behavior[EstimatorReceivable] = idle()

  private def idle(): Behavior[EstimatorReceivable] = Behaviors.withTimers { timer =>
    timer.startSingleTimer(Timeout, 5.second)
    // Wait for measurement data
    Behaviors.receive { (context, message) =>
      message match {
        case DroneMeasurement(leader, realVec, parentDrone) =>
          sendLocalState(realVec, leader, parentDrone)
        case Timeout =>
          context.log.info("Timed out waiting for measurement...")
          Behaviors.stopped
        case _ =>
          Behaviors.same
      }
    }
  }

  private def sendLocalState(measurement: ArrayRealVector, leader: ActorRef[LocalState], parentDrone: ActorRef[Estimate]): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
    // Need to do "new Array2DRowRealMatrix(<Matrix>.getData())" since inner result returns a RealMatrix 
    leader ! LocalState(measurement, new Array2DRowRealMatrix(H.transpose().multiply(MatrixUtils.inverse(R)).multiply(H).getData()), context.self)
    receiveGlobalState(leader, parentDrone)
  }

  private def receiveGlobalState(leader: ActorRef[LocalState], parentDrone: ActorRef[Estimate]): Behavior[EstimatorReceivable] = Behaviors.receive { (context, message) =>
    context.log.info(s"Received global state ${message}")
    message match {
      case GlobalState(avgMeasurement, avgCovarianceMatrix) => 
        estimating(avgMeasurement, avgCovarianceMatrix, leader, parentDrone)
      case _ =>
        Behaviors.same
    }
  }

  // Performs equations 22-25 from paper 5 (Distributed Kalman Filter with Embedded Consensus Filters)
  // avgMeasurement is y, avgCovarianceMatrix is S
  private def estimating(avgMeasurement: ArrayRealVector, avgCovarianceMatrix: Array2DRowRealMatrix, leader: ActorRef[LocalState], parentDrone: ActorRef[Estimate]): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
    // M = inv(inv(P) + S)
    val M = new Array2DRowRealMatrix(MatrixUtils.inverse(MatrixUtils.inverse(P).add(avgCovarianceMatrix)).getData())
    // xHat = xBar + M(y-Sx)
    val xHat = xBar.add(M.operate(avgMeasurement.subtract(avgCovarianceMatrix.operate(xBar))))
    // P+ = AMA'+BQB'
    P = A.multiply(M).multiply(new Array2DRowRealMatrix(A.transpose().getData())).add(new Array2DRowRealMatrix(B.transpose().getData()))
    // xBar = A(xHat)
    xBar = new ArrayRealVector(A.operate(xHat))

    // context.log.info(s"Estimator using \n y:${avgMeasurement}\n S:${avgCovarianceMatrix}\n P:${P}\n H:${H}\n xBar:${xBar}\n M:${M}\n xHat:${xHat}\n M(y-Sx):${M.operate(avgMeasurement.subtract(avgCovarianceMatrix.operate(xBar)))}")
    context.log.info(s"Sending estimate: ${xHat.getEntry(0)}")
    parentDrone ! Estimate(xHat.getEntry(0))
    idle()
  }

}
