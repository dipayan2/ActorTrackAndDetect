package com.example

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.collection.mutable.ListBuffer

sealed trait KalmanState
final case class LocalState(measurement: ArrayRealVector, invCovarianceMatrix: Array2DRowRealMatrix, sender: ActorRef[GlobalState]) extends KalmanState
final case class CovarianceMatrix(covarianceMatrix: Array2DRowRealMatrix) extends KalmanState
final case class MeasurementVector(measurementVector: ArrayRealVector) extends KalmanState

/**
  * The leader drone will spawn a GlobalStateCalc actor to calculate the average measurement
  * vector and covariance matrix from the local states of other drones in the network
  */
object GlobalStateCalc {
    // Constants
    val measurementVectorLength = 1

    def apply(numNodes: Int): Behavior[KalmanState] = calculateGlobalState(numNodes)

    private def calculateGlobalState(numNodes: Int): Behavior[KalmanState] = Behaviors.setup { context =>
        var statesReceived = 0 // number of local state messages received so far
        var avgMeasurement = new ArrayRealVector(measurementVectorLength)
        var networkNodes = new ListBuffer[ActorRef[GlobalState]]() // list of actors in leader's network we want to send the calculated global state to
        val avgCovarianceCalc = context.spawn(AvgCovarianceCalc(numNodes, context.self), "avgCovarianceCalc")
        val avgMeasurementCalc = context.spawn(AvgMeasurementCalc(numNodes, context.self), "avgMeasurmentCalc")
        Behaviors.receive { (context, message) =>
            message match {
                case LocalState(measurement, invCovarianceMatrix, sender) => 
                    statesReceived += 1
                    avgMeasurementCalc ! MeasurementVector(measurement)
                    avgCovarianceCalc ! CovarianceMatrix(invCovarianceMatrix)
                    // send global state when all messages received, note that this currently assumes numNodes stays constant
                    if (statesReceived == numNodes) {
                        receiveAverages(numNodes, networkNodes)
                    } else {
                        Behaviors.same
                    }
                case _ =>
                    Behaviors.same
            }
        }
    }

    private def receiveAverages(numNodes: Int, networkNodes: ListBuffer[ActorRef[GlobalState]]): Behavior[KalmanState] = Behaviors.setup { context =>
        var avgCovarianceMatrix: Option[Array2DRowRealMatrix] = None
        var avgMeasurementVector: Option[ArrayRealVector] = None
        Behaviors.receive { (context, message) =>
            message match {
                case CovarianceMatrix(covarianceMatrix) =>
                    avgCovarianceMatrix = Some(covarianceMatrix)
                case MeasurementVector(measurementVector) =>
                    avgMeasurementVector = Some(measurementVector)
                case _ =>
                    Behaviors.same
            }
            if (avgCovarianceMatrix != None && avgMeasurementVector != None) {
                networkNodes.foreach(node => {
                    context.log.info(s"Sending global state to ${node}\n")
                    node ! GlobalState(avgMeasurementVector.get, avgCovarianceMatrix.get)
                })
                calculateGlobalState(numNodes)
            } else {
                Behaviors.same
            }
        }
    }
}
