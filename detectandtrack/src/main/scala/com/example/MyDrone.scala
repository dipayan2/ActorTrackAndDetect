package com.example
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import org.apache.commons.math3.linear.ArrayRealVector


import scala.concurrent.duration._
import akka.actor.Actor

object DroneNode {
    trait Event
    case object Start extends Event
    trait KalmanMessage extends Event

    def apply(isLeader: Boolean): Behavior[Event] = Behaviors.setup { context =>
        val generator = context.spawn(DroneSensor(context.self), "generator")
        val estimator = context.spawn(DroneKalmanEstimator(1), "estimator")
        var globalCalc: Option[ActorRef[KalmanState]] = None
        if (isLeader)
            globalCalc = Some(context.spawn(GlobalStateCalc(1), "globalStateCalc"))
        waitForMeasurement(isLeader, generator, estimator, globalCalc)
    }

    def waitForMeasurement(isLeader: Boolean, generator: ActorRef[KalmanMessage], estimator: ActorRef[KalmanMessage], globalCalc: Option[ActorRef[KalmanState]]): Behavior[Event] = Behaviors.receive { (context, message) =>
        message match {
            case Measurement(measurement) =>
                estimator ! EstimatorInput(measurement, context.self, context.self)
            if (isLeader)
                routeLocalStates(isLeader, generator, estimator, globalCalc)
            else
                waitForEstimate(isLeader, generator, estimator, globalCalc)
        }
    }

    def routeLocalStates(isLeader: Boolean, generator: ActorRef[KalmanMessage], estimator: ActorRef[KalmanMessage], globalCalc: Option[ActorRef[KalmanState]]): Behavior[Event] = Behaviors.setup { context =>
        var statesReceived = 0 // number of local state messages received so far
        Behaviors.receive { (context, message) =>
            statesReceived += 1
            message match {
                case LocalState(measurement, invCovarianceMatrix, sender) => 
                    globalCalc match {
                        case Some(calc) => calc ! LocalState(measurement, invCovarianceMatrix, sender)
                        case None => context.log.error("Leader drone does not have global calc")
                    }
                case _ =>
                    Behaviors.same
            }
            if (statesReceived == 1)
                waitForEstimate(isLeader, generator, estimator, globalCalc)
            else
                Behaviors.same
        }
    }

    def waitForEstimate(isLeader: Boolean, generator: ActorRef[KalmanMessage], estimator: ActorRef[KalmanMessage], globalCalc: Option[ActorRef[KalmanState]]): Behavior[Event] = Behaviors.receive { (context, message) =>
        message match {
            case Estimate(estimate) =>
                generator ! Estimate(estimate)
            waitForMeasurement(isLeader, generator, estimator, globalCalc)
        }
    }
}