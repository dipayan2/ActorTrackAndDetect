package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._

trait TargetD extends SensorEvent
case object TargetAck extends TargetD
case class TargetData(data: Double, sender: ActorRef[SensorEvent]) extends TargetD
case class KalmanEstimateD(data: Double, sender: ActorRef[TargetD]) extends TargetD
// Measurement(data: Double, sender: ActorRef[SensorEvent])
// case object 

object TargetNode{

    // A target will be created based on the measurements of the sensor from the. Maybe we can keep track of the targets created 
    def apply(id: Int, dID:Int, parent: ActorRef[Event]): Behavior[SensorEvent] = Behaviors.setup{ context =>
        val tid = id
        val parentDroneID = dID
        val parentAddr = parent
        val estimator = context.spawn(KalmanEstimator(id,context.self),"estimator")
        context.log.info(s" Target ${tid} has been created and assigned to drone ${parentDroneID} ")
        // val estimator = context.spawn(KalmanEstimator(tid,context.self),"estimator")
        Behaviors.receiveMessage {
            case TargetData(data, sender) =>
                context.log.info(s" Target ${tid} actor received the measurement")
                sender ! TargetAck
                Behaviors.same
        }

    }
}