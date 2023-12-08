package com.example

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import org.apache.commons.math3.linear.RealVector
import org.apache.commons.math3.linear.ArrayRealVector

object AvgMeasurementCalc {
    val measurementVectorLength = 1

    def apply(numNodes: Int, globalStateCalculator: ActorRef[KalmanState]): Behavior[MeasurementVector] = calculateAverage(numNodes, globalStateCalculator)

    private def calculateAverage(numNodes: Int, globalStateCalculator: ActorRef[KalmanState]): Behavior[MeasurementVector] = Behaviors.setup { context =>
        var statesReceived = 0
        var avgMeasurementVector = new ArrayRealVector(measurementVectorLength)
        Behaviors.receive { (context, message) =>
            message match {
                case MeasurementVector(measurementVector) => 
                    statesReceived += 1
                    avgMeasurementVector = avgMeasurementVector.add(measurementVector)
                    // send global state when all messages received, note that this currently assumes numNodes stays constant
                    if (statesReceived == numNodes) {
                        avgMeasurementVector.mapDivideToSelf(numNodes)
                        globalStateCalculator ! MeasurementVector(avgMeasurementVector)
                        statesReceived = 0 
                        avgMeasurementVector = new ArrayRealVector(measurementVectorLength)
                    }
                    Behaviors.same
                case _ =>
                    Behaviors.same
            }
        }
    }
}
