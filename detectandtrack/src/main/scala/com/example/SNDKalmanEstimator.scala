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

object KalmanEstimator{

    def apply(id: Int, drone: ActorRef[SensorEvent]): Behavior[SensorEvent] = Behaviors.receive{ (context,message) =>
        
        message match{
        case Measurement(data,parent) =>
            context.log.info(s"Kalman Estimator for ${id}")
            val estimate = data+1 // We just 1 to the data. We will do the kalman calculation here
            parent ! Estimate(estimate,data,context.self)
            Behaviors.same
    }
}

}