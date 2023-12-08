package com.example

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.example.GlobalStateCalc

object AvgCovarianceCalc {
    val covMatRows = 1
    val covMatCols = 1

    def apply(numNodes: Int, globalStateCalculator: ActorRef[KalmanState]): Behavior[CovarianceMatrix] = calculateAverage(numNodes, globalStateCalculator)

    private def calculateAverage(numNodes: Int, globalStateCalculator: ActorRef[KalmanState]): Behavior[CovarianceMatrix] = Behaviors.setup { context =>
        var statesReceived = 0
        var avgCovarianceMatrix = new Array2DRowRealMatrix(covMatRows, covMatCols)
        Behaviors.receive { (context, message) =>
            message match {
                case CovarianceMatrix(covarianceMatrix) => 
                    statesReceived += 1
                    avgCovarianceMatrix = avgCovarianceMatrix.add(covarianceMatrix)
                    // send global state when all messages received, note that this currently assumes numNodes stays constant
                    if (statesReceived == numNodes) {
                        avgCovarianceMatrix = new Array2DRowRealMatrix(avgCovarianceMatrix.scalarMultiply(1.0/numNodes).getData())
                        globalStateCalculator ! CovarianceMatrix(avgCovarianceMatrix)
                        statesReceived = 0 
                        avgCovarianceMatrix = new Array2DRowRealMatrix(covMatRows, covMatCols)
                    }
                    Behaviors.same
                case _ =>
                    Behaviors.same
            }
        }
    }
}
